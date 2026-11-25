package com.openminis.app.github

/**
 * The hard gate every GitHub tool call passes through.
 *
 * Pure logic over [RepoRules] so it can be unit-tested without Android. The
 * agent's prompt also states the rules, but a prompt is advice: a model can
 * misread it, or be steered by text inside a repository it was asked to read.
 * The decision made here is the one that counts.
 */
object GitHubPolicy {

    /** What the policy decided about one request. */
    sealed class Verdict {
        data class Allowed(val repo: String, val access: RepoAccess) : Verdict()

        /** [reason] is shown to the model verbatim so it can correct itself. */
        data class Denied(val reason: String) : Verdict()
    }

    /** The operation a tool wants to perform. */
    enum class Op {
        /** List the repositories the account can reach. */
        LIST_REPOS,

        /** Read a file, list a directory, read a pull request. */
        READ,

        /** Create a branch, commit files, open a pull request. */
        WRITE,
    }

    /**
     * Whether `repo` is visible at all under the grant.
     *
     * WHITELIST: only a named repository with a level above DISABLED.
     * ALL: everything is visible, and a repository with no explicit rule is
     * READ — the broader the scope, the more the default leans to reading.
     */
    fun accessFor(rules: RepoRules, repo: String): RepoAccess {
        val name = normalize(repo)
        if (name.isEmpty()) return RepoAccess.DISABLED
        val explicit = rules.repos[name]
        return when (rules.scope) {
            RepoScope.WHITELIST -> explicit ?: RepoAccess.DISABLED
            RepoScope.ALL -> explicit ?: RepoAccess.READ
        }
    }

    /**
     * Decides one call. [repo] is `owner/name`; for [Op.LIST_REPOS] it is
     * ignored.
     */
    fun check(rules: RepoRules, op: Op, repo: String?, branch: String? = null): Verdict {
        if (op == Op.LIST_REPOS) return Verdict.Allowed("", RepoAccess.READ)
        val name = normalize(repo ?: return Verdict.Denied("repo is required"))
        if (!isValidRepoName(name)) return Verdict.Denied("repo must be owner/name, got \"$repo\"")
        val access = accessFor(rules, name)
        if (access == RepoAccess.DISABLED) {
            // Not "access denied": a repository outside the grant does not
            // exist for this agent, and saying so leaks nothing about why.
            return Verdict.Denied(
                "$name is not in your GitHub grant. Only repositories the user has " +
                    "enabled in Settings › Agent Runtime › GitHub are reachable; " +
                    "call github_repo_list to see them.",
            )
        }
        if (op == Op.WRITE && access != RepoAccess.WRITE) {
            return Verdict.Denied(
                "$name is read-only in your GitHub grant. The user has not allowed " +
                    "changes to it; report what you would have changed and stop.",
            )
        }
        if (op == Op.WRITE) {
            val b = branch?.trim().orEmpty()
            if (b.isEmpty()) return Verdict.Denied("branch is required for a write")
            if (isProtectedBranch(b)) {
                return Verdict.Denied(
                    "direct commits to \"$b\" are not allowed. Create a new branch with " +
                        "github_create_branch, commit there, and open a pull request.",
                )
            }
        }
        return Verdict.Allowed(name, access)
    }

    /**
     * Filters a repository listing down to what the grant exposes, and
     * attaches each one's level so the model can see what it may do where.
     */
    fun visible(rules: RepoRules, repos: List<String>): Map<String, RepoAccess> {
        val out = LinkedHashMap<String, RepoAccess>()
        for (raw in repos) {
            val name = normalize(raw)
            val access = accessFor(rules, name)
            if (access != RepoAccess.DISABLED) out[name] = access
        }
        return out
    }

    /** Branches a WRITE grant still may not commit to directly. */
    fun isProtectedBranch(branch: String): Boolean {
        val b = branch.trim().lowercase()
        return b == "main" || b == "master" || b == "trunk" || b == "develop" ||
            b.startsWith("release/") || b == "release"
    }

    /** `Owner/Name` → `owner/name`; anything without exactly one slash → "". */
    fun normalize(repo: String): String {
        val trimmed = repo.trim().removePrefix("https://github.com/")
            .removePrefix("http://github.com/").trimEnd('/')
        val parts = trimmed.split('/')
        if (parts.size != 2) return ""
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        if (!isValidSegment(owner) || !isValidSegment(name)) return ""
        return "${owner.lowercase()}/${name.lowercase()}"
    }

    fun isValidRepoName(name: String): Boolean = normalize(name) == name

    private fun isValidSegment(s: String): Boolean =
        s.isNotEmpty() && s.length <= 100 && s.all { c ->
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.'
        } && !s.startsWith('.') && !s.startsWith('-')

    /**
     * Paths that are never readable or writable, whatever the repository
     * level. A write token plus one of these is a way to run code in CI or
     * to walk off with a signing key, which is not what "may edit project X"
     * was meant to hand out.
     */
    private val blockedPathPrefixes = listOf(
        ".github/",
        ".git/",
        ".circleci/",
        ".gitlab-ci",
    )

    private val blockedPathSuffixes = listOf(
        ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key",
    )

    private val blockedPathNames = listOf(
        "keystore.properties", "secrets.xml", ".env", ".env.local", ".env.production",
        "google-services.json", "local.properties", "id_rsa", "id_ed25519",
    )

    /**
     * Whether `path` may be touched at all. Applied to reads as well as
     * writes: a key committed by mistake is as good as leaked if the agent
     * can print it.
     */
    fun isPathAllowed(path: String): Boolean {
        val p = path.trim().replace('\\', '/').trimStart('/')
        if (p.isEmpty() || p.contains("..") || p.contains("//")) return false
        val lower = p.lowercase()
        if (blockedPathPrefixes.any { lower.startsWith(it) }) return false
        if (blockedPathNames.any { lower == it || lower.endsWith("/$it") }) return false
        if (blockedPathSuffixes.any { lower.endsWith(it) }) return false
        return true
    }

    fun pathDenialReason(path: String): String =
        "\"$path\" is a protected path (CI config, signing material or secrets) and " +
            "is not available through the GitHub tools."
}
