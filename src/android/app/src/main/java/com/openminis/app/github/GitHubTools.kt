package com.openminis.app.github

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's GitHub tools.
 *
 * Six tools over [GitHubClient], each gated by [GitHubPolicy] before any
 * request is made. The gate reads the stored grant fresh on every call, so a
 * change on the settings page takes effect on the next tool call with no
 * restart.
 *
 * The tool set is only offered once a GitHub account is signed in
 * ([definitions] returns an empty list otherwise), so the model never sees a
 * tool that can only answer "not signed in".
 */
object GitHubTools {

    const val LIST_REPOS = "github_repo_list"
    const val READ_FILE = "github_file_read"
    const val LIST_DIR = "github_list_dir"
    const val WRITE_FILE = "github_file_write"
    const val CREATE_BRANCH = "github_create_branch"
    const val CREATE_PR = "github_create_pr"

    private val toolNames = setOf(LIST_REPOS, READ_FILE, LIST_DIR, WRITE_FILE, CREATE_BRANCH, CREATE_PR)

    fun isGitHubTool(name: String): Boolean = name in toolNames

    /** Definitions for the signed-in account, or an empty list when there is none. */
    fun definitions(context: Context): List<AgentToolDefinition> {
        val store = GitHubStore(context)
        if (!store.hasToken()) return emptyList()
        val rules = store.rules()
        val writeRepos = rules.repos.filterValues { it == RepoAccess.WRITE }.keys.sorted()
        val scopeNote = when (rules.scope) {
            RepoScope.WHITELIST ->
                "Only repositories the user enabled are reachable; anything else is refused. " +
                    "Writable: ${writeRepos.joinToString().ifEmpty { "(none)" }}."
            RepoScope.ALL ->
                "All repositories of the account are readable. " +
                    "Writable: ${writeRepos.joinToString().ifEmpty { "(none)" }}."
        }
        return listOf(
            listReposDefinition(),
            readFileDefinition(),
            listDirDefinition(),
            writeFileDefinition(scopeNote),
            createBranchDefinition(),
            createPullRequestDefinition(),
        )
    }

    /** True when this tool exists at all for this install: a token is present. */
    fun isEnabled(context: Context): Boolean = GitHubStore(context).hasToken()

    // ------------------------------------------------------------------ //
    // Dispatch
    // ------------------------------------------------------------------ //

    fun execute(context: Context, name: String, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val toolTitle = args.optString("tool_title").takeIf { it.isNotBlank() } ?: defaultTitle(name)
        val store = GitHubStore(context)
        val token = store.token()
            ?: return ToolExecutionResult(
                "GitHub is not signed in. Ask the user to sign in under Settings › Agent Runtime › GitHub.",
                false,
                toolTitle = toolTitle,
            )
        val rules = store.rules()
        val repo = args.optString("repo")
        val branch = args.optString("branch")
        val path = args.optString("path")
        val op = when (name) {
            LIST_REPOS -> GitHubPolicy.Op.LIST_REPOS
            READ_FILE, LIST_DIR -> GitHubPolicy.Op.READ
            else -> GitHubPolicy.Op.WRITE
        }
        // The gate comes first: a refused call never reaches the network, and
        // the refusal is the same whatever the approval mode is set to.
        val verdict = when (name) {
            CREATE_PR -> GitHubPolicy.check(rules, GitHubPolicy.Op.WRITE, repo, args.optString("head"))
            else -> GitHubPolicy.check(rules, op, repo, branch)
        }
        if (verdict is GitHubPolicy.Verdict.Denied) {
            return ToolExecutionResult("Refused: ${verdict.reason}", false, toolTitle = toolTitle)
        }
        if ((name == READ_FILE || name == LIST_DIR || name == WRITE_FILE) && !GitHubPolicy.isPathAllowed(path)) {
            return ToolExecutionResult(
                "Refused: ${GitHubPolicy.pathDenialReason(path)}",
                false,
                toolTitle = toolTitle,
            )
        }

        val client = GitHubClient()
        return try {
            when (name) {
                LIST_REPOS -> listRepos(client, token, rules, toolTitle)
                READ_FILE -> readFile(client, token, repo, path, args.optString("ref"), args, toolTitle)
                LIST_DIR -> listDir(client, token, repo, path, args.optString("ref"), toolTitle)
                WRITE_FILE -> writeFile(client, token, repo, branch, path, args, toolTitle)
                CREATE_BRANCH -> createBranch(client, token, repo, branch, args.optString("from"), toolTitle)
                CREATE_PR -> createPullRequest(client, token, repo, args, toolTitle)
                else -> ToolExecutionResult("Unknown GitHub tool: $name", false, toolTitle = toolTitle)
            }
        } catch (e: GitHubApiException) {
            ToolExecutionResult("GitHub error: ${e.message}", false, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("GitHub error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }

    /** Blocking bridge: the tool dispatcher is synchronous, the client is suspending. */
    private fun <T> runBlocking(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        kotlinx.coroutines.runBlocking { block(this) }

    // ------------------------------------------------------------------ //
    // Tools
    // ------------------------------------------------------------------ //

    private fun listRepos(client: GitHubClient, token: String, rules: RepoRules, title: String): ToolExecutionResult =
        runBlocking {
            val repos = client.listRepos(token)
            val visible = GitHubPolicy.visible(rules, repos.map { it.fullName })
            if (visible.isEmpty()) {
                return@runBlocking ToolExecutionResult(
                    "No repositories are enabled. The user can allow some under Settings › Agent Runtime › GitHub.",
                    true,
                    toolTitle = title,
                )
            }
            val byName = repos.associateBy { it.fullName }
            val lines = StringBuilder()
            lines.append("${visible.size} repositories reachable. Access: read = list/read only; write = also commit on new branches and open pull requests.\n")
            for ((name, access) in visible) {
                val repo = byName[name]
                val meta = buildString {
                    append(access.wire)
                    if (repo != null) {
                        append(" · default ").append(repo.defaultBranch)
                        if (repo.isPrivate) append(" · private")
                        if (repo.isFork) append(" · fork")
                        repo.pushedAt.take(10).takeIf { it.isNotBlank() }?.let { append(" · pushed ").append(it) }
                    }
                }
                lines.append("- $name  [$meta]")
                repo?.description?.takeIf { it.isNotBlank() }?.let { lines.append("  — ").append(it.take(80)) }
                lines.append('\n')
            }
            ToolExecutionResult(lines.toString().trimEnd(), true, toolTitle = title)
        }

    private fun readFile(
        client: GitHubClient,
        token: String,
        repo: String,
        path: String,
        ref: String?,
        args: JSONObject,
        title: String,
    ): ToolExecutionResult = runBlocking {
        val file = client.readFile(token, repo, path, ref)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", DEFAULT_READ_LINES).coerceIn(1, MAX_READ_LINES)
        val maxLength = args.optInt("max_length", DEFAULT_MAX_CHARS).coerceIn(500, MAX_CHARS)
        val lines = file.content.lines()
        val slice = lines.drop(offset - 1).take(limit)
        if (slice.isEmpty()) {
            return@runBlocking ToolExecutionResult(
                "(no lines at offset $offset; $repo/${file.path} has ${lines.size} lines)",
                true,
                toolTitle = title,
            )
        }
        // Line numbers are part of the contract: a following write can name an
        // exact location, and a reader can tell what was skipped.
        var body = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
        if (body.length > maxLength) body = body.take(maxLength) + "\n… truncated at $maxLength chars"
        val more = lines.size - (offset - 1) - slice.size
        if (more > 0) body += "\n… $more more lines (continue with offset ${offset + slice.size})"
        val header = "$repo/${file.path} @ ${ref?.takeIf { it.isNotBlank() } ?: "default branch"} · ${file.size} bytes · sha ${file.sha.take(10)}\n"
        ToolExecutionResult(header + body, true, toolTitle = title)
    }

    private fun listDir(
        client: GitHubClient,
        token: String,
        repo: String,
        path: String,
        ref: String?,
        title: String,
    ): ToolExecutionResult = runBlocking {
        val entries = client.listDirectory(token, repo, path, ref)
        if (entries.isEmpty()) {
            return@runBlocking ToolExecutionResult("(empty directory: $repo/${path.ifBlank { "/" }})", true, toolTitle = title)
        }
        val body = entries.joinToString("\n") { e ->
            val kind = if (e.type == "dir") "d" else "-"
            "$kind  ${e.path}${if (e.type != "dir" && e.size > 0) "  (${formatSize(e.size)})" else ""}"
        }
        ToolExecutionResult(
            "$repo/${path.ifBlank { "/" }} @ ${ref?.takeIf { it.isNotBlank() } ?: "default branch"} · ${entries.size} entries\n$body",
            true,
            toolTitle = title,
        )
    }

    private fun writeFile(
        client: GitHubClient,
        token: String,
        repo: String,
        branch: String,
        path: String,
        args: JSONObject,
        title: String,
    ): ToolExecutionResult = runBlocking {
        val content = args.optString("content")
        val message = args.optString("message").ifBlank { "Update ${path.trimStart('/')}" }
        val previous = client.readFileOrNull(token, repo, path, branch)
        val commit = client.commitFiles(token, repo, branch, message, mapOf(path.trimStart('/') to content))
        val summary = buildString {
            append(if (previous == null) "Created " else "Updated ").append(repo).append('/').append(path.trimStart('/'))
            append(" on ").append(branch).append('\n')
            append("commit ").append(commit.sha.take(10)).append(" · ").append(commit.url).append('\n')
            if (previous != null) {
                val before = previous.content.lines().size
                val after = content.lines().size
                append("lines ").append(before).append(" → ").append(after)
            }
            append("\nOpen a pull request with github_create_pr when the change is ready for review.")
        }
        ToolExecutionResult(summary, true, toolTitle = title)
    }

    private fun createBranch(
        client: GitHubClient,
        token: String,
        repo: String,
        branch: String,
        from: String?,
        title: String,
    ): ToolExecutionResult = runBlocking {
        if (!is_validBranchName(branch)) {
            return@runBlocking ToolExecutionResult(
                "Refused: \"$branch\" is not a usable branch name.",
                false,
                toolTitle = title,
            )
        }
        val created = client.createBranch(token, repo, branch, from)
        ToolExecutionResult(
            "Created branch $created in $repo from ${from?.takeIf { it.isNotBlank() } ?: "the default branch"}.\n" +
                "Commit to it with github_file_write(branch=\"$created\"), then open a pull request.",
            true,
            toolTitle = title,
        )
    }

    private fun createPullRequest(
        client: GitHubClient,
        token: String,
        repo: String,
        args: JSONObject,
        title: String,
    ): ToolExecutionResult = runBlocking {
        val head = args.optString("head")
        val base = args.optString("base").ifBlank { "main" }
        val prTitle = args.optString("title").ifBlank { "Update from OpenMinis" }
        val body = args.optString("body")
        val pr = client.createPullRequest(token, repo, head, base, prTitle, body)
        ToolExecutionResult(
            "Pull request #${pr.number} opened: ${pr.title}\n${pr.url}\n${pr.headRef} → ${pr.baseRef} · head ${pr.headSha.take(10)}",
            true,
            toolTitle = title,
        )
    }

    // ------------------------------------------------------------------ //
    // Definitions
    // ------------------------------------------------------------------ //

    private fun listReposDefinition() = AgentToolDefinition(
        name = LIST_REPOS,
        description = "List the GitHub repositories this account may use through the GitHub tools, " +
            "with each one's access level (read / write). Call this first: a repository not listed " +
            "here will be refused by every other github_* tool.",
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    private fun readFileDefinition() = AgentToolDefinition(
        name = READ_FILE,
        description = "Read a text file from a GitHub repository (default branch unless ref is given). " +
            "Returns the content with line numbers. Binary and oversized files are refused; " +
            "CI config, signing keys and secret files are never readable.",
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
            "repo" to AgentToolParam("string", "Repository as owner/name, exactly as listed by github_repo_list."),
            "path" to AgentToolParam("string", "Path inside the repository, e.g. README.md or src/main/App.kt."),
            "ref" to AgentToolParam("string", "Branch, tag or commit sha. Defaults to the repository's default branch."),
            "offset" to AgentToolParam("integer", "1-based line to start from (default 1). If a read was truncated, its header says where to continue."),
            "limit" to AgentToolParam("integer", "Maximum lines to return (default 2000, max 20000)."),
            "max_length" to AgentToolParam("integer", "Maximum characters to return (default 15000, max 60000)."),
        ),
        required = listOf("tool_title", "repo", "path"),
        propertyOrdering = listOf("tool_title", "repo", "path", "ref", "offset", "limit", "max_length"),
    )

    private fun listDirDefinition() = AgentToolDefinition(
        name = LIST_DIR,
        description = "List a directory of a GitHub repository: file and folder names with sizes. " +
            "Use it to explore a project before reading files.",
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
            "repo" to AgentToolParam("string", "Repository as owner/name."),
            "path" to AgentToolParam("string", "Directory path; empty or \"/\" for the repository root."),
            "ref" to AgentToolParam("string", "Branch, tag or sha. Defaults to the default branch."),
        ),
        required = listOf("tool_title", "repo"),
        propertyOrdering = listOf("tool_title", "repo", "path", "ref"),
    )

    private fun writeFileDefinition(scopeNote: String) = AgentToolDefinition(
        name = WRITE_FILE,
        description = "Create or replace one text file in a GitHub repository on a branch you created with " +
            "github_create_branch, as a single commit. Only repositories with write access accept this. " +
            scopeNote,
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
            "repo" to AgentToolParam("string", "Repository as owner/name."),
            "branch" to AgentToolParam("string", "The branch to commit to. Must not be main/master: commit to a new branch and open a pull request."),
            "path" to AgentToolParam("string", "File path inside the repository. Parent directories are created as needed."),
            "content" to AgentToolParam("string", "Full new content of the file."),
            "message" to AgentToolParam("string", "Commit message. Defaults to \"Update <path>\"."),
        ),
        required = listOf("tool_title", "repo", "branch", "path", "content"),
        propertyOrdering = listOf("tool_title", "repo", "branch", "path", "content", "message"),
    )

    private fun createBranchDefinition() = AgentToolDefinition(
        name = CREATE_BRANCH,
        description = "Create a new branch in a GitHub repository, from the default branch or from `from`. " +
            "The first step of every change: commits go to the new branch, never to main.",
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
            "repo" to AgentToolParam("string", "Repository as owner/name."),
            "branch" to AgentToolParam("string", "Name of the branch to create, e.g. fix/readme-typo."),
            "from" to AgentToolParam("string", "Branch or sha to start from. Defaults to the default branch."),
        ),
        required = listOf("tool_title", "repo", "branch"),
        propertyOrdering = listOf("tool_title", "repo", "branch", "from"),
    )

    private fun createPullRequestDefinition() = AgentToolDefinition(
        name = CREATE_PR,
        description = "Open a pull request in a GitHub repository from a branch you committed to. " +
            "Report the URL to the user; do not merge it yourself.",
        parameters = mapOf(
            "tool_title" to TITLE_PARAM,
            "repo" to AgentToolParam("string", "Repository as owner/name."),
            "head" to AgentToolParam("string", "The branch holding the changes."),
            "base" to AgentToolParam("string", "The branch to merge into. Defaults to the repository's default branch."),
            "title" to AgentToolParam("string", "Pull request title."),
            "body" to AgentToolParam("string", "Pull request description: what changed and why."),
        ),
        required = listOf("tool_title", "repo", "head"),
        propertyOrdering = listOf("tool_title", "repo", "head", "base", "title", "body"),
    )

    private val TITLE_PARAM = AgentToolParam(
        "string",
        "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'List reachable GitHub repositories', 'Read README of a project'). Use the same language as the user.",
    )

    // ------------------------------------------------------------------ //

    private fun defaultTitle(name: String): String = when (name) {
        LIST_REPOS -> "List GitHub repositories"
        READ_FILE -> "Read GitHub file"
        LIST_DIR -> "List GitHub directory"
        WRITE_FILE -> "Write GitHub file"
        CREATE_BRANCH -> "Create GitHub branch"
        CREATE_PR -> "Open GitHub pull request"
        else -> "GitHub"
    }

    private fun isValidBranchName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 200) return false
        if (n.startsWith('-') || n.endsWith('/') || n.endsWith(".lock") || n.contains("..") || n.contains(" ")) return false
        if (GitHubPolicy.isProtectedBranch(n)) return false
        return n.all { it.isLetterOrDigit() || it in "-_/." }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    private const val DEFAULT_READ_LINES = 2000
    private const val MAX_READ_LINES = 20000
    private const val DEFAULT_MAX_CHARS = 15_000
    private const val MAX_CHARS = 60_000

    @Suppress("unused")
    private fun JSONArray.toStrings(): List<String> = (0 until length()).map { optString(it) }
}
