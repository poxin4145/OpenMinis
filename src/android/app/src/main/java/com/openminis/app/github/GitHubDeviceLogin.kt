package com.openminis.app.github

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * GitHub sign-in through the Device Authorization Grant (RFC 8628): the app
 * shows a short code, the user enters it at github.com/login/device, and
 * this polls the token endpoint until they approve.
 *
 * Why device flow and not a pasted token: the user never handles a secret,
 * and the token GitHub issues can be revoked from one page. The cost is that
 * the OAuth App must exist and have Device Flow enabled — its client id is
 * public and lives in the manifest.
 *
 * Scopes: `repo` is asked for because reading a private repository and
 * opening a pull request both need it; fine-grained restrictions are then
 * applied by [GitHubPolicy] on top, since an OAuth token's scope is
 * account-wide and cannot be narrowed to one repository.
 */
class GitHubDeviceLogin(
    context: Context,
    private val http: OkHttpClient = GitHubClient.defaultClient(),
) {

    /** Issued by step 1; the UI shows [userCode] and opens [verificationUri]. */
    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    sealed class Poll {
        data class Success(val accessToken: String, val scopes: List<String>) : Poll()
        object Pending : Poll()
        data class Denied(val message: String) : Poll()
    }

    private val clientId: String by lazy {
        val meta = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(META_CLIENT_ID)
        } catch (_: Exception) {
            null
        }
        meta?.trim().orEmpty()
    }

    val isLoginAvailable: Boolean get() = clientId.isNotEmpty()

    /** Step 1. Throws [GitHubApiException] when the client id is missing or GitHub refuses. */
    suspend fun requestDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        if (clientId.isEmpty()) {
            throw GitHubApiException(
                "GitHub login is not configured: no OAuth App client id (manifest meta-data \"$META_CLIENT_ID\").",
                0,
            )
        }
        val payload = JSONObject().put("client_id", clientId).put("scope", SCOPE)
        val status = IntArray(1)
        val body = post("$DEVICE_CODE_URL", payload.toString(), status)
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status[0] !in 200..299 || json == null) {
            val error = json?.optString("error").orEmpty()
            val description = json?.optString("error_description").orEmpty()
            throw GitHubApiException(
                when (error) {
                    "device_flow_disabled" ->
                        "Device Flow is not enabled for the GitHub OAuth App. Open the app's settings " +
                            "on GitHub and tick \"Enable Device Flow\"."
                    "incorrect_client_credentials" ->
                        "The GitHub OAuth App client id is not recognised. Check meta-data \"$META_CLIENT_ID\"."
                    else -> "GitHub device authorization failed (${status[0]}): ${description.ifEmpty { error }}"
                },
                status[0],
            )
        }
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        val uri = json.optString("verification_uri")
        if (deviceCode.isEmpty() || userCode.isEmpty() || uri.isEmpty()) {
            throw GitHubApiException("GitHub device authorization response is incomplete", status[0])
        }
        Log.i(TAG, "Device code issued; expires in ${json.optLong("expires_in", 0)}s")
        DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSeconds = json.optLong("expires_in", 0L).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0L).takeIf { it > 0 } ?: 5L,
        )
    }

    /**
     * Step 3. Polls at the server's interval (plus 5 s on `slow_down`, per
     * RFC 8628 §3.5) until approval, refusal or expiry. Returns once.
     */
    suspend fun pollForToken(auth: DeviceAuthorization): Poll = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
        var interval = auth.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            val payload = JSONObject()
                .put("client_id", clientId)
                .put("device_code", auth.deviceCode)
                .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            val status = IntArray(1)
            val body = try {
                post(TOKEN_URL, payload.toString(), status)
            } catch (e: Exception) {
                Log.w(TAG, "token poll failed, retrying: ${e.message}")
                continue
            }
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val token = json.optString("access_token")
            if (status[0] in 200..299 && token.isNotEmpty()) {
                Log.i(TAG, "Device login succeeded")
                return@withContext Poll.Success(
                    accessToken = token,
                    scopes = json.optString("scope").split(' ').filter { it.isNotBlank() },
                )
            }
            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> return@withContext Poll.Denied("The sign-in was cancelled on GitHub.")
                "expired_token" -> return@withContext Poll.Denied("The code expired; start sign-in again.")
                "unsupported_grant_type", "incorrect_device_code", "incorrect_client_credentials" ->
                    return@withContext Poll.Denied(
                        "GitHub refused the device login (${json.optString("error")}). " +
                            "Check that the OAuth App has Device Flow enabled.",
                    )
                else -> Unit
            }
        }
        Poll.Denied("Timed out waiting for approval.")
    }

    private fun post(url: String, json: String, statusOut: IntArray): String {
        val req = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { response ->
            statusOut[0] = response.code
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TAG = "GitHubDeviceLogin"
        const val META_CLIENT_ID = "GitHubOAuthClientID"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"

        /**
         * `repo` covers read/write on public and private repositories plus
         * pull requests. Nothing broader (no `admin:*`, no `delete_repo`):
         * the tools never touch settings or delete anything.
         */
        const val SCOPE = "repo"
    }
}
