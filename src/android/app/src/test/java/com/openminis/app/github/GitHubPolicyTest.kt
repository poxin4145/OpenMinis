package com.openminis.app.github

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-github-tools] Pins the grant the GitHub tools enforce.
 *
 * The policy is the only thing between a model and a repository, and the
 * model's own text is not trusted to describe it — a repository it was asked
 * to read may contain instructions. Every case here is a way a call could
 * reach something the user did not allow; each must land on Denied.
 */
class GitHubPolicyTest {

    private val whitelist = RepoRules(
        scope = RepoScope.WHITELIST,
        repos = mapOf(
            "me/open" to RepoAccess.READ,
            "me/app" to RepoAccess.WRITE,
        ),
    )

    // -- scope -----------------------------------------------------------------

    @Test
    fun `whitelist hides an unlisted repository`() {
        assertEquals(RepoAccess.DISABLED, GitHubPolicy.accessFor(whitelist, "me/other"))
        assertEquals(RepoAccess.READ, GitHubPolicy.accessFor(whitelist, "me/open"))
        assertEquals(RepoAccess.WRITE, GitHubPolicy.accessFor(whitelist, "me/app"))
    }

    @Test
    fun `all scope reads everything and keeps explicit write`() {
        val all = whitelist.copy(scope = RepoScope.ALL)
        assertEquals(RepoAccess.READ, GitHubPolicy.accessFor(all, "me/other"))
        assertEquals(RepoAccess.READ, GitHubPolicy.accessFor(all, "me/open"))
        assertEquals(RepoAccess.WRITE, GitHubPolicy.accessFor(all, "me/app"))
    }

    @Test
    fun `all scope does not turn a disabled repository back on`() {
        val all = whitelist.copy(scope = RepoScope.ALL, repos = mapOf("me/secret" to RepoAccess.DISABLED))
        assertEquals(RepoAccess.DISABLED, GitHubPolicy.accessFor(all, "me/secret"))
    }

    @Test
    fun `read is refused in whitelist and allowed in all`() {
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.READ, "me/other") is GitHubPolicy.Verdict.Denied)
        assertTrue(
            GitHubPolicy.check(whitelist.copy(scope = RepoScope.ALL), GitHubPolicy.Op.READ, "me/other")
                is GitHubPolicy.Verdict.Allowed,
        )
    }

    // -- write -----------------------------------------------------------------

    @Test
    fun `write needs write level even in all scope`() {
        val all = whitelist.copy(scope = RepoScope.ALL)
        val verdict = GitHubPolicy.check(all, GitHubPolicy.Op.WRITE, "me/other", branch = "feature/x")
        assertTrue(verdict is GitHubPolicy.Verdict.Denied)
        assertTrue((verdict as GitHubPolicy.Verdict.Denied).reason.contains("read-only"))
    }

    @Test
    fun `write on a write repository and a fresh branch is allowed`() {
        val verdict = GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, "me/app", branch = "feature/x")
        assertTrue(verdict is GitHubPolicy.Verdict.Allowed)
    }

    @Test
    fun `write without a branch is refused`() {
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, "me/app", branch = null) is GitHubPolicy.Verdict.Denied)
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, "me/app", branch = "  ") is GitHubPolicy.Verdict.Denied)
    }

    @Test
    fun `direct commits to protected branches are refused`() {
        for (branch in listOf("main", "master", "MAIN", " develop ", "release/1.0", "trunk")) {
            val verdict = GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, "me/app", branch = branch)
            assertTrue("expected refusal for $branch", verdict is GitHubPolicy.Verdict.Denied)
        }
    }

    @Test
    fun `protected branch check does not over-match`() {
        for (branch in listOf("mainpage", "maintenance", "releases", "development", "feature/main-thing")) {
            assertTrue("expected allowed for $branch", !GitHubPolicy.isProtectedBranch(branch))
        }
    }

    // -- name normalisation ------------------------------------------------------

    @Test
    fun `repository names are normalised case and trailing slash`() {
        assertEquals("me/open", GitHubPolicy.normalize("ME/Open/"))
        assertEquals("me/open", GitHubPolicy.normalize("https://github.com/ME/Open"))
        assertEquals("me/open", GitHubPolicy.normalize("me/open.git"))
        assertEquals("", GitHubPolicy.normalize("me/open/extra"))
        assertEquals("", GitHubPolicy.normalize("justaname"))
        assertEquals("", GitHubPolicy.normalize("../etc/passwd"))
    }

    @Test
    fun `a write grant cannot be reached by look-alike names`() {
        // Case and a trailing slash describe the SAME repository; that is fine.
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, "ME/APP/", branch = "feature/x") is GitHubPolicy.Verdict.Allowed)
        // These are different repositories, or not repositories at all: nothing.
        for (repo in listOf("me/app.evil/x", "me/../app", "me/app/..", "me//app", "me/")) {
            val verdict = GitHubPolicy.check(whitelist, GitHubPolicy.Op.WRITE, repo, branch = "feature/x")
            assertTrue("expected refusal for $repo", verdict is GitHubPolicy.Verdict.Denied)
        }
    }

    @Test
    fun `empty repo is refused for every op but list`() {
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.READ, null) is GitHubPolicy.Verdict.Denied)
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.READ, "") is GitHubPolicy.Verdict.Denied)
        assertTrue(GitHubPolicy.check(whitelist, GitHubPolicy.Op.LIST_REPOS, null) is GitHubPolicy.Verdict.Allowed)
    }

    // -- visibility -------------------------------------------------------------

    @Test
    fun `visible drops disabled and keeps order`() {
        val visible = GitHubPolicy.visible(whitelist, listOf("z/last", "me/other", "a/first", "me/open", "me/app"))
        assertEquals(listOf("me/open", "me/app"), visible.keys.toList())
        assertEquals(RepoAccess.READ, visible["me/open"])
        assertEquals(RepoAccess.WRITE, visible["me/app"])
    }

    @Test
    fun `visible in all scope keeps everything`() {
        val all = whitelist.copy(scope = RepoScope.ALL)
        val visible = GitHubPolicy.visible(all, listOf("me/other", "me/open"))
        assertEquals(RepoAccess.READ, visible["me/other"])
        assertEquals(RepoAccess.READ, visible["me/open"])
    }

    // -- protected paths -------------------------------------------------------

    @Test
    fun `ci config signing material and secrets are never readable`() {
        for (path in listOf(
            ".github/workflows/build.yml",
            "repo/.github/workflows/x.yml",
            ".git/config",
            "app/release.keystore",
            "keystore.properties",
            "app/google-services.json",
            ".env",
            "config/.env.production",
            "deploy/server.pem",
            "certs/signing.p12",
            "src/main/res/values/secrets.xml",
            "ssh/id_ed25519",
        )) {
            assertFalse("expected refusal for $path", GitHubPolicy.isPathAllowed(path))
        }
    }

    @Test
    fun `ordinary source files are allowed`() {
        for (path in listOf(
            "README.md",
            "src/main/App.kt",
            "app/build.gradle.kts",
            "docs/ci.md",
            ".gitignore",
            "github/Client.kt",
            "releases/notes.txt",
            "env.example",
            "docs/github-actions-setup.md",
            "github/Client.kt",
        )) {
            assertTrue("expected allowed for $path", GitHubPolicy.isPathAllowed(path))
        }
    }

    @Test
    fun `nested ci config is still blocked`() {
        // A .github folder under a vendored or submodule path is still CI
        // GitHub runs, and a keystore under app/ is still a keystore.
        for (path in listOf(
            "sub/lib/.github/workflows/deploy.yml",
            "vendor/tool/.circleci/config.yml",
            "app/keystore.properties",
            "backend/.env",
            "android/.gitlab-ci.yml",
            ".travis.yml",
            "Jenkinsfile",
        )) {
            assertFalse("expected refusal for $path", GitHubPolicy.isPathAllowed(path))
        }
    }

    @Test
    fun `traversal and empty paths are refused`() {
        for (path in listOf("", "  ", "../x", "a/../../b", "a//b", "/..")) {
            assertFalse("expected refusal for $path", GitHubPolicy.isPathAllowed(path))
        }
    }

    // -- persistence -----------------------------------------------------------

    @Test
    fun `rules survive a json round trip`() {
        val json = whitelist.toJson()
        val back = RepoRules.fromJson(JSONObject(json.toString()))
        assertEquals(whitelist, back)
    }

    @Test
    fun `unknown wire values fall back to the safe side`() {
        assertEquals(RepoScope.WHITELIST, RepoScope.fromWire("everything"))
        assertEquals(RepoAccess.DISABLED, RepoAccess.fromWire("admin"))
        val back = RepoRules.fromJson(JSONObject("""{"scope":"nope","repos":{"a/b":"root"}}"""))
        assertEquals(RepoScope.WHITELIST, back.scope)
        assertEquals(RepoAccess.DISABLED, back.repos["a/b"])
    }
}
