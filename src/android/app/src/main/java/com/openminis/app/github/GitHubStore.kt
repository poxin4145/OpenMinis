package com.openminis.app.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the signed-in GitHub account may do with one repository. */
enum class RepoAccess(val wire: String) {
    /** Not even listed. The agent cannot see that the repository exists. */
    DISABLED("disabled"),

    /** List, read files, read directories, read pull requests. Nothing else. */
    READ("read"),

    /** Everything READ allows, plus commits on new branches and pull requests. */
    WRITE("write"),
    ;

    companion object {
        fun fromWire(value: String?): RepoAccess =
            entries.firstOrNull { it.wire == value } ?: DISABLED

        /** Least access of the two, used when two rules could both apply. */
        fun minOf(a: RepoAccess, b: RepoAccess): RepoAccess =
            if (a.ordinal <= b.ordinal) a else b
    }
}

/** How the repository list is scoped. */
enum class RepoScope(val wire: String) {
    /**
     * Only the repositories explicitly granted access in [RepoRules.repos]
     * are visible; everything else is refused.
     */
    WHITELIST("whitelist"),

    /**
     * Every repository the token can reach is available. The access level is
     * still per repository; a repository with no explicit rule is READ.
     */
    ALL("all"),
    ;

    companion object {
        fun fromWire(value: String?): RepoScope =
            entries.firstOrNull { it.wire == value } ?: WHITELIST
    }
}

/**
 * The user's grant: which repositories exist for the agent, and what it may
 * do in each.
 *
 * This is the single source of truth for the policy. The agent's tool
 * descriptions tell the model what it may do, but the check is enforced in
 * [GitHubPolicy] on every call, because a description is advice and the
 * transcript is untrusted input.
 */
data class RepoRules(
    val scope: RepoScope = RepoScope.WHITELIST,
    /** `owner/name` (lower-case) to access level. */
    val repos: Map<String, RepoAccess> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("scope", scope.wire)
        put("repos", JSONObject().apply {
            repos.forEach { (name, access) -> put(name, access.wire) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): RepoRules {
            val repos = LinkedHashMap<String, RepoAccess>()
            json.optJSONObject("repos")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    repos[key] = RepoAccess.fromWire(obj.optString(key))
                }
            }
            return RepoRules(
                scope = RepoScope.fromWire(json.optString("scope")),
                repos = repos,
            )
        }
    }
}

/** The signed-in GitHub account, as reported by `GET /user`. */
data class GitHubAccount(
    val login: String,
    val id: Long,
    val avatarUrl: String,
)

/**
 * Stored GitHub state: token, account and the repository grant.
 *
 * The token is sealed with AES-GCM under a key that lives in Android
 * Keystore. A GitHub token is long-lived and grants repository access until
 * it is revoked by hand, so unlike the upstream login credentials (which
 * expire and refresh) it is not written in the clear.
 */
class GitHubStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ //
    // Token + account
    // ------------------------------------------------------------------ //

    fun hasToken(): Boolean = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()

    fun token(): String? {
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching { open(blob) }.getOrNull()?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
    }

    fun saveToken(token: String, account: GitHubAccount) {
        val sealed = seal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_TOKEN, sealed)
            .putString(
                KEY_ACCOUNT,
                JSONObject().apply {
                    put("login", account.login)
                    put("id", account.id)
                    put("avatar", account.avatarUrl)
                }.toString(),
            )
            .apply()
    }

    fun account(): GitHubAccount? {
        if (!hasToken()) return null
        val raw = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            GitHubAccount(
                login = obj.optString("login"),
                id = obj.optLong("id"),
                avatarUrl = obj.optString("avatar"),
            )
        }.getOrNull()?.takeIf { it.login.isNotEmpty() }
    }

    /** Forgets the token; the repository grant is kept so re-login restores it. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT).apply()
    }

    // ------------------------------------------------------------------ //
    // Repository grant
    // ------------------------------------------------------------------ //

    fun rules(): RepoRules {
        val raw = prefs.getString(KEY_RULES, null) ?: return RepoRules()
        return runCatching { RepoRules.fromJson(JSONObject(raw)) }.getOrDefault(RepoRules())
    }

    fun saveRules(rules: RepoRules) {
        prefs.edit().putString(KEY_RULES, rules.toJson().toString()).apply()
    }

    // ------------------------------------------------------------------ //
    // Sealing
    // ------------------------------------------------------------------ //

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun open(blob: String): ByteArray {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "empty blob" }
        val ivLength = bytes[0].toInt().and(0xFF)
        require(ivLength in 12..16 && bytes.size > 1 + ivLength) { "bad blob" }
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val sealed = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Generated inside the keystore: the material never enters the app's
        // storage, so a copy of the preferences file alone is useless.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "github"
        const val KEY_TOKEN = "token"
        const val KEY_ACCOUNT = "account"
        const val KEY_RULES = "rules"
        const val KEY_ALIAS = "wbhub-github-token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
