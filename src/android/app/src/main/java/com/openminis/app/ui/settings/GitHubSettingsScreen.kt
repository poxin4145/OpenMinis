package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.github.GitHubClient
import com.openminis.app.github.GitHubDeviceLogin
import com.openminis.app.github.GitHubPolicy
import com.openminis.app.github.GitHubRepo
import com.openminis.app.github.GitHubStore
import com.openminis.app.github.RepoAccess
import com.openminis.app.github.RepoRules
import com.openminis.app.github.RepoScope
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Agent Runtime › GitHub.
 *
 * Two things are decided here and nowhere else: which GitHub account the
 * agent acts as, and which repositories it may touch at what level. The
 * GitHub tools read the same [RepoRules] this page writes, so what is on
 * screen is exactly what the tools honour.
 *
 * The page never shows a token. Sign-in is the device flow: a code appears,
 * the user approves on github.com, the token lands sealed in [GitHubStore].
 * Signing out deletes it and keeps the grant, so signing back in restores
 * the same repository levels.
 */
@Composable
fun GitHubSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GitHubStore(context) }
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf(store.account()) }
    var rules by remember { mutableStateOf(store.rules()) }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var loadingRepos by remember { mutableStateOf(false) }
    var repoError by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deviceAuth by remember { mutableStateOf<GitHubDeviceLogin.DeviceAuthorization?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showScopePicker by remember { mutableStateOf(false) }

    fun save(newRules: RepoRules) {
        rules = newRules
        store.saveRules(newRules)
    }

    fun refresh() {
        scope.launch {
            val token = store.token() ?: return@launch
            loadingRepos = true
            repoError = null
            try {
                repos = GitHubClient().listRepos(token)
            } catch (e: Exception) {
                repoError = e.message ?: e.javaClass.simpleName
            } finally {
                loadingRepos = false
            }
        }
    }

    // Refresh on entry and whenever the account changes, so a repository
    // pushed elsewhere is not stuck with a stale entry here.
    LaunchedEffect(account?.login) { if (account != null) refresh() }

    SettingsScaffold(title = stringResource(R.string.github_settings_title), onBack = onBack) {
        // -- Account --
        SettingsSection(footer = stringResource(R.string.github_account_footer)) {
            val current = account
            if (current == null) {
                SettingsRow(
                    title = stringResource(R.string.github_sign_in),
                    subtitle = stringResource(R.string.github_sign_in_subtitle),
                    icon = Icons.Default.Code,
                    iconColor = GITHUB_COLOR,
                    onClick = {
                        loginError = null
                        loginJob = scope.launch {
                            busy = true
                            try {
                                val login = GitHubDeviceLogin(context)
                                val auth = login.requestDeviceAuthorization()
                                deviceAuth = auth
                                when (val result = login.pollForToken(auth)) {
                                    is GitHubDeviceLogin.Poll.Success -> {
                                        val who = withContext(Dispatchers.IO) { GitHubClient().whoami(result.accessToken) }
                                        store.saveToken(result.accessToken, who)
                                        account = who
                                        deviceAuth = null
                                    }
                                    is GitHubDeviceLogin.Poll.Denied -> loginError = result.message
                                    GitHubDeviceLogin.Poll.Pending -> Unit
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                loginError = e.message ?: e.javaClass.simpleName
                                deviceAuth = null
                            } finally {
                                busy = false
                            }
                        }
                    },
                    trailing = {
                        if (busy && deviceAuth == null) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    },
                )
            } else {
                SettingsRow(
                    title = current.login,
                    subtitle = stringResource(R.string.github_signed_in_as),
                    icon = Icons.Default.Code,
                    iconColor = GITHUB_COLOR,
                    onClick = null,
                    trailing = {
                        MinisTextButton(onClick = {
                            store.clearToken()
                            account = null
                            repos = emptyList()
                            repoError = null
                        }) { Text(stringResource(R.string.github_sign_out)) }
                    },
                )
            }
            loginError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (account == null) {
            SettingsSection {
                Text(
                    stringResource(R.string.github_setup_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            return@SettingsScaffold
        }

        // -- Scope --
        SettingsSection(footer = stringResource(R.string.github_scope_footer)) {
            SettingsValueRow(
                title = stringResource(R.string.github_scope),
                value = stringResource(
                    if (rules.scope == RepoScope.ALL) R.string.github_scope_all else R.string.github_scope_whitelist,
                ),
                onClick = { showScopePicker = true },
            )
        }

        // -- Repositories --
        SettingsSection(
            header = stringResource(R.string.github_repositories),
            footer = stringResource(
                if (rules.scope == RepoScope.ALL) R.string.github_repos_footer_all else R.string.github_repos_footer_whitelist,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loadingRepos) stringResource(R.string.github_loading) else stringResource(R.string.github_repo_count, repos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(onClick = { showSearch = !showSearch }) { Text(stringResource(R.string.github_search)) }
                MinisTextButton(onClick = { refresh() }) { Text(stringResource(R.string.github_refresh)) }
            }
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.github_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            repoError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val filtered = repos.filter { matches(it, query, rules) }
            if (filtered.isEmpty() && !loadingRepos && repoError == null) {
                Text(
                    stringResource(R.string.github_no_repos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            for (repo in filtered) {
                RepoAccessRow(
                    repo = repo,
                    access = GitHubPolicy.accessFor(rules, repo.fullName),
                    onAccess = { access ->
                        val next = rules.repos.toMutableMap()
                        if (access == RepoAccess.DISABLED) next.remove(repo.fullName) else next[repo.fullName] = access
                        save(rules.copy(repos = next))
                    },
                )
            }
        }

        // -- One tap back to read-only --
        if (rules.repos.values.any { it == RepoAccess.WRITE }) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.github_disable_all_writes),
                    subtitle = stringResource(R.string.github_disable_all_writes_subtitle),
                    icon = Icons.Default.CloudOff,
                    iconColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        save(
                            rules.copy(
                                repos = rules.repos.mapValues { (_, access) ->
                                    if (access == RepoAccess.WRITE) RepoAccess.READ else access
                                },
                            ),
                        )
                    },
                )
            }
        }
    }

    deviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.verificationUri,
            onCancel = {
                loginJob?.cancel()
                deviceAuth = null
            },
            title = stringResource(R.string.github_login_title),
            instructions = stringResource(R.string.github_login_instructions),
        )
    }

    if (showScopePicker) {
        AlertDialog(
            onDismissRequest = { showScopePicker = false },
            title = { Text(stringResource(R.string.github_scope)) },
            text = {
                Column {
                    SettingsChoiceRow(
                        title = stringResource(R.string.github_scope_whitelist),
                        selected = rules.scope == RepoScope.WHITELIST,
                        onSelect = {
                            save(rules.copy(scope = RepoScope.WHITELIST))
                            showScopePicker = false
                        },
                        showDivider = true,
                    )
                    SettingsChoiceRow(
                        title = stringResource(R.string.github_scope_all),
                        selected = rules.scope == RepoScope.ALL,
                        onSelect = {
                            save(rules.copy(scope = RepoScope.ALL))
                            showScopePicker = false
                        },
                        showDivider = false,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                MinisTextButton(onClick = { showScopePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** One repository with its two-way toggle (read / write); tapping the active level disables it. */
@Composable
private fun RepoAccessRow(repo: GitHubRepo, access: RepoAccess, onAccess: (RepoAccess) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            repo.fullName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            if (repo.isPrivate) add(stringResource(R.string.github_repo_private))
            if (repo.isFork) add(stringResource(R.string.github_repo_fork))
            if (repo.pushedAt.isNotBlank()) add(stringResource(R.string.github_repo_pushed, repo.pushedAt.take(10)))
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (level in listOf(RepoAccess.READ, RepoAccess.WRITE)) {
                FilterChip(
                    selected = access == level,
                    onClick = { onAccess(if (access == level) RepoAccess.DISABLED else level) },
                    label = {
                        Text(
                            stringResource(
                                if (level == RepoAccess.WRITE) R.string.github_access_write else R.string.github_access_read,
                            ),
                        )
                    },
                )
            }
            if (access == RepoAccess.READ && repo.defaultBranch.isNotBlank()) {
                Text(
                    stringResource(R.string.github_repo_default_branch, repo.defaultBranch),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * WHITELIST lists the enabled repositories, plus whatever the search box
 * matches so it can be added; ALL lists everything the token reaches.
 */
private fun matches(repo: GitHubRepo, query: String, rules: RepoRules): Boolean {
    val q = query.trim().lowercase()
    if (q.isNotEmpty()) {
        val hit = repo.fullName.contains(q) || repo.description.orEmpty().lowercase().contains(q)
        if (!hit) return false
    }
    return rules.scope == RepoScope.ALL || q.isNotEmpty() || rules.repos.containsKey(repo.fullName)
}

private val GITHUB_COLOR = Color(0xFF24292F)
