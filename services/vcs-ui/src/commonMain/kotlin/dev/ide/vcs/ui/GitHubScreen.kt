package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiForgePullRequest
import dev.ide.ui.backend.UiVcsRemote
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_add_remote
import dev.ide.vcs.ui.generated.resources.vcs_draft
import dev.ide.vcs.ui.generated.resources.vcs_github
import dev.ide.vcs.ui.generated.resources.vcs_new_pull_request
import dev.ide.vcs.ui.generated.resources.vcs_no_remote
import dev.ide.vcs.ui.generated.resources.vcs_pr_base
import dev.ide.vcs.ui.generated.resources.vcs_pr_body
import dev.ide.vcs.ui.generated.resources.vcs_pr_create
import dev.ide.vcs.ui.generated.resources.vcs_pr_from
import dev.ide.vcs.ui.generated.resources.vcs_pr_title
import dev.ide.vcs.ui.generated.resources.vcs_publish
import dev.ide.vcs.ui.generated.resources.vcs_publish_body
import dev.ide.vcs.ui.generated.resources.vcs_publish_description
import dev.ide.vcs.ui.generated.resources.vcs_publish_name
import dev.ide.vcs.ui.generated.resources.vcs_publish_needs_commit
import dev.ide.vcs.ui.generated.resources.vcs_publish_private
import dev.ide.vcs.ui.generated.resources.vcs_publish_title
import dev.ide.vcs.ui.generated.resources.vcs_pull_requests
import dev.ide.vcs.ui.generated.resources.vcs_pull_requests_empty
import dev.ide.vcs.ui.generated.resources.vcs_remote_name
import dev.ide.vcs.ui.generated.resources.vcs_remote_url
import dev.ide.vcs.ui.generated.resources.vcs_remotes
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_github
import dev.ide.vcs.ui.generated.resources.vcs_clone_sign_in
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The GitHub side of the open project: publish it as a new repository when it has no remote, otherwise list
 * the open pull requests and offer to open one from the current branch. Remotes are managed here too, since
 * that is what decides which repository the rest of this screen talks about.
 */
@Composable
internal fun GitHubScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val status by vcs.status.collectAsState()
    val accounts by vcs.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()

    var remotes by remember { mutableStateOf(emptyList<UiVcsRemote>()) }
    var pulls by remember { mutableStateOf(emptyList<UiForgePullRequest>()) }
    var reload by remember { mutableStateOf(0) }

    var repoName by remember { mutableStateOf("") }
    var repoDescription by remember { mutableStateOf("") }
    var repoPrivate by remember { mutableStateOf(true) }

    var remoteName by remember { mutableStateOf("origin") }
    var remoteUrl by remember { mutableStateOf("") }

    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    var prBase by remember { mutableStateOf("main") }

    LaunchedEffect(reload, accounts.size) {
        remotes = vcs.remotes()
        pulls = if (remotes.isNotEmpty() && accounts.isNotEmpty()) vcs.pullRequests() else emptyList()
        if (repoName.isBlank()) repoName = ctx.backend.project.name
    }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            if (result.message.isNotBlank()) feedback.show(result.message, isError = !result.ok)
            if (result.authRequired) ctx.openScreen(VcsService.SCREEN_ACCOUNTS)
            reload++
        }
    }

    ExpressiveScaffold(title = stringResource(Res.string.vcs_github), onBack = ctx::back, large = false) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FeedbackStrip(feedback)

            if (accounts.isEmpty()) {
                Card {
                    Text(
                        stringResource(Res.string.vcs_clone_sign_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryButton(
                        stringResource(Res.string.vcs_sign_in_github),
                        { ctx.openScreen(VcsService.SCREEN_ACCOUNTS) },
                        icon = CaIcons.account,
                    )
                }
            }

            if (remotes.isEmpty()) {
                Card {
                    CardTitle(stringResource(Res.string.vcs_publish_title), CaIcons.cloudUpload)
                    Text(
                        stringResource(Res.string.vcs_publish_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.unborn) {
                        Text(
                            stringResource(Res.string.vcs_publish_needs_commit),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    VcsField(repoName, { repoName = it }, stringResource(Res.string.vcs_publish_name), leading = CaIcons.folder)
                    VcsField(
                        repoDescription,
                        { repoDescription = it },
                        stringResource(Res.string.vcs_publish_description),
                        leading = CaIcons.docText,
                    )
                    VcsCheckRow(
                        label = stringResource(Res.string.vcs_publish_private),
                        checked = repoPrivate,
                        onToggle = { repoPrivate = !repoPrivate },
                    )
                    PrimaryButton(
                        stringResource(Res.string.vcs_publish),
                        { perform { vcs.publishToForge(repoName, repoDescription, repoPrivate) } },
                        icon = CaIcons.cloudUpload,
                    )
                }
            } else {
                Card {
                    CardTitle(stringResource(Res.string.vcs_pull_requests), CaIcons.gitPullRequest)
                    if (pulls.isEmpty()) {
                        Text(
                            stringResource(Res.string.vcs_pull_requests_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        pulls.forEach { pr ->
                            PullRequestRow(pr) {
                                if (ctx.fileActions.canOpenUrl) ctx.fileActions.openUrl(pr.webUrl)
                            }
                        }
                    }
                }
                Card {
                    CardTitle(stringResource(Res.string.vcs_new_pull_request), CaIcons.gitPullRequest)
                    if (status.branch.isNotBlank()) {
                        Text(
                            stringResource(Res.string.vcs_pr_from, status.branch),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    VcsField(prTitle, { prTitle = it }, stringResource(Res.string.vcs_pr_title), leading = CaIcons.docText)
                    VcsField(
                        prBody,
                        { prBody = it },
                        stringResource(Res.string.vcs_pr_body),
                        singleLine = false,
                        minHeight = 70,
                    )
                    VcsField(prBase, { prBase = it }, stringResource(Res.string.vcs_pr_base), leading = CaIcons.gitBranch)
                    PrimaryButton(
                        stringResource(Res.string.vcs_pr_create),
                        { perform { vcs.createPullRequest(prTitle, prBody, prBase) } },
                    )
                }
            }

            Card {
                CardTitle(stringResource(Res.string.vcs_remotes), CaIcons.share)
                if (remotes.isEmpty()) {
                    Text(
                        stringResource(Res.string.vcs_no_remote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    remotes.forEach { remote ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                remote.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                remote.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                VcsField(remoteName, { remoteName = it }, stringResource(Res.string.vcs_remote_name), leading = CaIcons.share)
                VcsField(remoteUrl, { remoteUrl = it }, stringResource(Res.string.vcs_remote_url), leading = CaIcons.share)
                PrimaryButton(
                    stringResource(Res.string.vcs_add_remote),
                    { perform { vcs.addRemote(remoteName, remoteUrl) } },
                    icon = CaIcons.plus,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PullRequestRow(pr: UiForgePullRequest, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "#${pr.number} ${pr.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pr.draft) Chip(stringResource(Res.string.vcs_draft))
                Chip("${pr.headBranch} → ${pr.baseBranch}")
                if (pr.updatedLabel.isNotBlank()) Chip(pr.updatedLabel)
            }
        }
        Icon(CaIcons.arrowRight, null, Modifier.size(16.dp), tint = scheme.outline)
    }
}
