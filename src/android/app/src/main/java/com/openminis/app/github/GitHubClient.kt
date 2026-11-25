package com.openminis.app.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failure that carries the HTTP status, so callers can name the cause. */
class GitHubApiException(message: String, val status: Int) : IOException(message)

/** One repository as the list endpoint reports it. */
data class GitHubRepo(
    val fullName: String,
    val description: String,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val defaultBranch: String,
    val pushedAt: String,
)

/** One entry of a directory listing. */
data class GitHubEntry(val path: String, val type: String, val size: Long)

/** A text file fetched at a ref. */
data class GitHubFile(val path: String, val content: String, val sha: String, val size: Long)

/** A pull request, with the head sha it was listed at. */
data class GitHubPull(
    val number: Int,
    val title: String,
    val state: String,
    val headRef: String,
    val baseRef: String,
    val url: String,
    val headSha: String,
)

/** What a successful commit reports back. */
data class GitHubCommit(val sha: String, val branch: String, val url: String)

/**
 * Thin REST v3 client over OkHttp. Only the calls the GitHub tools need,
 * each returning parsed data or throwing [GitHubApiException].
 *
 * Every request is built by [request] so the token, the API version and the
 * user agent are set in one place. The token is passed per call rather than
 * held, so the client never outlives a login.
 */
class GitHubClient(
    private val http: OkHttpClient = defaultClient(),
) {

    suspend fun whoami(token: String): GitHubAccount = withContext(Dispatchers.IO) {
        val obj = JSONObject(call(request(token, "/user")))
        val login = obj.optString("login")
        if (login.isEmpty()) throw GitHubApiException("GitHub returned no login for this token", 0)
        GitHubAccount(
            login = login,
            id = obj.optLong("id"),
            avatarUrl = obj.optString("avatar_url"),
        )
    }

    /**
     * Every repository the token can reach, walking the pagination. Both
     * owned and collaborating repositories are asked for; organisations are
     * not, which keeps a long `?affiliation=` list from being paged through
     * for an account that has none.
     */
    suspend fun listRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val out = ArrayList<GitHubRepo>()
        var page = 1
        while (page <= MAX_PAGES) {
            val array = JSONArray(
                call(
                    request(
                        token,
                        "/user/repos?per_page=$PAGE_SIZE&page=$page&sort=pushed&affiliation=owner,collaborator",
                    ),
                ),
            )
            if (array.length() == 0) break
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                out += GitHubRepo(
                    fullName = row.optString("full_name").lowercase(),
                    description = row.optString("description"),
                    isPrivate = row.optBoolean("private"),
                    isFork = row.optBoolean("fork"),
                    defaultBranch = row.optString("default_branch").ifEmpty { "main" },
                    pushedAt = row.optString("pushed_at"),
                )
            }
            if (array.length() < PAGE_SIZE) break
            page++
        }
        out.sortByDescending { it.pushedAt }
        out
    }

    suspend fun listDirectory(token: String, repo: String, path: String, ref: String?): List<GitHubEntry> =
        withContext(Dispatchers.IO) {
            val clean = path.trim().trimStart('/').trimEnd('/')
            val query = buildString {
                append("/repos/$repo/contents")
                if (clean.isNotEmpty()) append("/").append(encodePath(clean))
                append("?per_page=$PAGE_SIZE")
                if (!ref.isNullOrBlank()) append("&ref=").append(urlEncode(ref))
            }
            val body = call(request(token, query))
            val array = try {
                JSONArray(body)
            } catch (_: Exception) {
                // A file path lands here when the caller thought it was a
                // directory; report it rather than failing to parse.
                throw GitHubApiException("\"$path\" is a file, not a directory", 0)
            }
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                GitHubEntry(
                    path = row.optString("path"),
                    type = row.optString("type"),
                    size = row.optLong("size"),
                )
            }
        }

    /**
     * Reads one text file through the contents API. Files above one megabyte
     * are refused: the contents API returns them only through the blob
     * endpoint, and a file that large does not belong in a model's context
     * anyway.
     */
    suspend fun readFile(token: String, repo: String, path: String, ref: String?): GitHubFile =
        withContext(Dispatchers.IO) {
            val clean = path.trim().trimStart('/')
            if (clean.isEmpty()) throw GitHubApiException("path is required", 0)
            val query = buildString {
                append("/repos/$repo/contents/").append(encodePath(clean))
                if (!ref.isNullOrBlank()) append("?ref=").append(urlEncode(ref))
            }
            val status = IntArray(1)
            val body = call(request(token, query), status)
            if (status[0] == 404) throw GitHubApiException("no such file in $repo: $clean", 404)
            val obj = JSONObject(body)
            if (obj.optString("type") == "dir") {
                throw GitHubApiException("\"$clean\" is a directory; use github_list_dir", 0)
            }
            val size = obj.optLong("size")
            if (size > MAX_FILE_BYTES) {
                throw GitHubApiException("\"$clean\" is ${size / 1024} KB, above the 1 MB read limit", 0)
            }
            val encoding = obj.optString("encoding")
            val content = when (encoding) {
                "base64" -> decodeBase64(obj.optString("content"))
                "none" -> obj.optString("content")
                else -> throw GitHubApiException("unsupported encoding \"$encoding\" for $clean", 0)
            }
            if (content.indexOf('\u0000') >= 0) {
                throw GitHubApiException("\"$clean\" looks like a binary file", 0)
            }
            GitHubFile(path = obj.optString("path"), content = content, sha = obj.optString("sha"), size = size)
        }

    /** Resolves a ref (branch, tag or sha) to a commit sha. */
    suspend fun resolveRef(token: String, repo: String, ref: String): String =
        withContext(Dispatchers.IO) {
            val body = call(request(token, "/repos/$repo/commits/${urlEncode(ref)}"))
            val sha = JSONObject(body).optString("sha")
            if (sha.isEmpty()) throw GitHubApiException("could not resolve ref \"$ref\" in $repo", 0)
            sha
        }

    /** Creates `branch` at `from` (default: the repository's default branch). */
    suspend fun createBranch(token: String, repo: String, branch: String, from: String?): String =
        withContext(Dispatchers.IO) {
            val base = from?.takeIf { it.isNotBlank() } ?: defaultBranch(token, repo)
            val sha = resolveRef(token, repo, base)
            val payload = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha)
            val status = IntArray(1)
            val body = call(
                request(token, "/repos/$repo/git/refs", "POST", payload.toString()),
                status,
            )
            if (status[0] == 422) {
                throw GitHubApiException("branch \"$branch\" already exists in $repo", 422)
            }
            val created = JSONObject(body).optJSONObject("ref")?.optString("ref").orEmpty()
            created.removePrefix("refs/heads/").ifEmpty { branch }
        }

    private suspend fun defaultBranch(token: String, repo: String): String {
        val obj = JSONObject(call(request(token, "/repos/$repo")))
        return obj.optString("default_branch").ifEmpty { "main" }
    }

    /**
     * Commits `files` to `branch` as one commit, through the Git Data API
     * (blobs → tree → commit → update ref).
     *
     * Using the data API rather than one `PUT /contents` per file keeps the
     * change atomic: every file lands in the same commit, and the ref is
     * moved only once, with the sha it was expected to be at. If someone
     * pushed in the meantime the update is refused (422) instead of silently
     * overwriting their work.
     *
     * A `null` content deletes the file.
     */
    suspend fun commitFiles(
        token: String,
        repo: String,
        branch: String,
        message: String,
        files: Map<String, String?>,
    ): GitHubCommit = withContext(Dispatchers.IO) {
        if (files.isEmpty()) throw GitHubApiException("no files to commit", 0)
        val headRef = JSONObject(call(request(token, "/repos/$repo/git/ref/heads/${urlEncode(branch)}")))
        val headSha = headRef.optJSONObject("object")?.optString("sha")
            ?: throw GitHubApiException("branch \"$branch\" not found in $repo", 404)
        val headCommit = JSONObject(call(request(token, "/repos/$repo/git/commits/$headSha")))
        val baseTreeSha = headCommit.optJSONObject("tree")?.optString("sha")
            ?: throw GitHubApiException("commit $headSha has no tree", 0)

        val tree = JSONArray()
        for ((path, content) in files) {
            val clean = path.trim().trimStart('/')
            if (content == null) {
                tree.put(JSONObject().put("path", clean).put("mode", "100644").put("type", "blob").put("sha", null))
            } else {
                val blobPayload = JSONObject()
                    .put("content", content)
                    .put("encoding", "utf-8")
                val blob = JSONObject(
                    call(request(token, "/repos/$repo/git/blobs", "POST", blobPayload.toString())),
                )
                val blobSha = blob.optString("sha")
                if (blobSha.isEmpty()) throw GitHubApiException("blob creation failed for $clean", 0)
                tree.put(
                    JSONObject()
                        .put("path", clean)
                        .put("mode", "100644")
                        .put("type", "blob")
                        .put("sha", blobSha),
                )
            }
        }

        val newTree = JSONObject(
            call(
                request(
                    token,
                    "/repos/$repo/git/trees",
                    "POST",
                    JSONObject()
                        .put("base_tree", baseTreeSha)
                        .put("tree", tree)
                        .toString(),
                ),
            ),
        ).optString("sha")
        if (newTree.isEmpty()) throw GitHubApiException("tree creation failed", 0)

        val commitSha = JSONObject(
            call(
                request(
                    token,
                    "/repos/$repo/git/commits",
                    "POST",
                    JSONObject()
                        .put("message", message.ifBlank { "Update from OpenMinis" })
                        .put("tree", newTree)
                        .put("parents", JSONArray().put(headSha))
                        .toString(),
                ),
            ),
        ).optString("sha")
        if (commitSha.isEmpty()) throw GitHubApiException("commit creation failed", 0)

        val updateStatus = IntArray(1)
        call(
            request(
                token,
                "/repos/$repo/git/refs/heads/${urlEncode(branch)}",
                "PATCH",
                JSONObject().put("sha", commitSha).put("force", false).toString(),
            ),
            updateStatus,
        )
        if (updateStatus[0] == 422) {
            throw GitHubApiException(
                "branch \"$branch\" moved while the commit was being built; nothing was written. " +
                    "Re-read the branch and retry.",
                422,
            )
        }
        val html = "https://github.com/$repo/commit/$commitSha"
        GitHubCommit(sha = commitSha, branch = branch, url = html)
    }

    /** Opens a pull request from `head` to `base`. */
    suspend fun createPullRequest(
        token: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): GitHubPull = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("head", head)
            .put("base", base)
            .put("title", title)
            .put("body", body)
        val status = IntArray(1)
        val resp = call(request(token, "/repos/$repo/pulls", "POST", payload.toString()), status)
        if (status[0] == 422) {
            val message = runCatching { JSONObject(resp).optString("message") }.getOrNull()
            throw GitHubApiException(message ?: "pull request rejected: $head → $base", 422)
        }
        val obj = JSONObject(resp)
        GitHubPull(
            number = obj.optInt("number"),
            title = obj.optString("title"),
            state = obj.optString("state"),
            headRef = obj.optJSONObject("head")?.optString("ref").orEmpty(),
            baseRef = obj.optJSONObject("base")?.optString("ref").orEmpty(),
            url = obj.optString("html_url"),
            headSha = obj.optJSONObject("head")?.optString("sha").orEmpty(),
        )
    }

    suspend fun listPullRequests(token: String, repo: String, state: String): List<GitHubPull> =
        withContext(Dispatchers.IO) {
            val body = call(
                request(
                    token,
                    "/repos/$repo/pulls?state=${urlEncode(state)}&per_page=$PAGE_SIZE&sort=updated&direction=desc",
                ),
            )
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                GitHubPull(
                    number = row.optInt("number"),
                    title = row.optString("title"),
                    state = row.optString("state"),
                    headRef = row.optJSONObject("head")?.optString("ref").orEmpty(),
                    baseRef = row.optJSONObject("base")?.optString("ref").orEmpty(),
                    url = row.optString("html_url"),
                    headSha = row.optJSONObject("head")?.optString("sha").orEmpty(),
                )
            }
        }

    /** Reads one file as text (same limits as [readFile]); used by write tools to show a diff. */
    suspend fun readFileOrNull(token: String, repo: String, path: String, ref: String?): GitHubFile? =
        try {
            readFile(token, repo, path, ref)
        } catch (e: GitHubApiException) {
            if (e.status == 404) null else throw e
        }

    // ------------------------------------------------------------------ //
    // HTTP
    // ------------------------------------------------------------------ //

    private fun request(token: String, path: String, method: String = "GET", body: String? = null): Request {
        if (!path.startsWith("/")) throw IllegalArgumentException("path must start with /")
        val builder = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
        if (method == "GET") {
            builder.get()
        } else {
            builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    /** Performs [req]; non-2xx becomes [GitHubApiException]. [statusOut] receives the code. */
    private fun call(req: Request, statusOut: IntArray = IntArray(1)): String {
        http.newCall(req).execute().use { response ->
            val code = response.code
            statusOut[0] = code
            val text = response.body?.string().orEmpty()
            if (code in 200..299) return text
            val detail = runCatching {
                val obj = JSONObject(text)
                obj.optString("message").ifEmpty { obj.optString("error") }
            }.getOrNull().orEmpty()
            throw GitHubApiException(
                when {
                    code == 401 -> "GitHub rejected the token (401). Sign in again in Settings › Agent Runtime › GitHub."
                    code == 403 && text.contains("rate limit", ignoreCase = true) ->
                        "GitHub rate limit reached (403). Try again later."
                    code == 403 -> "GitHub refused the request (403): ${detail.ifEmpty { "token lacks this permission" }}"
                    code == 404 -> "not found (404): ${detail.ifEmpty { "repository or path" }}"
                    else -> "GitHub returned $code: ${detail.ifEmpty { text.take(200) }}"
                },
                code,
            )
        }
    }

    companion object {
        const val API = "https://api.github.com"
        private const val USER_AGENT = "OpenMinis-Android/1.15"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
        const val MAX_FILE_BYTES = 1024L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Encodes each path segment so a slash inside a file name survives. */
        fun encodePath(path: String): String = path.split('/').joinToString("/") { urlEncode(it) }

        fun decodeBase64(s: String): String {
            // GitHub wraps base64 bodies at 60 columns; the newlines are not data.
            val cleaned = s.replace("\n", "").replace("\r", "")
            return String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }
    }
}
