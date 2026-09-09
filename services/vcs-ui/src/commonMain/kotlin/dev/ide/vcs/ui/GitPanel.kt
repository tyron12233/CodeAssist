package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiVcsChange
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.UiVcsStatus
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.icons.CaIcons
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_hint_changes
import dev.ide.vcs.ui.generated.resources.vcs_hint_commit
import dev.ide.vcs.ui.generated.resources.vcs_hint_conflicts
import dev.ide.vcs.ui.generated.resources.vcs_hint_push
import dev.ide.vcs.ui.generated.resources.vcs_hint_staged
import dev.ide.vcs.ui.generated.resources.vcs_menu_file
import dev.ide.vcs.ui.generated.resources.vcs_menu_file_history
import dev.ide.vcs.ui.generated.resources.vcs_menu_more
import dev.ide.vcs.ui.generated.resources.vcs_menu_repository
import dev.ide.vcs.ui.generated.resources.vcs_menu_sync
import dev.ide.vcs.ui.generated.resources.vcs_menu_view_changes
import dev.ide.vcs.ui.generated.resources.vcs_no_remote_short
import dev.ide.vcs.ui.generated.resources.vcs_push_after_commit
import dev.ide.vcs.ui.generated.resources.vcs_set_up_remote
import dev.ide.vcs.ui.generated.resources.vcs_stash_changes
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_branch
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_discard
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_fetch
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_github
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_history
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_pull
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_push
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_refresh
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_resolved
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_stage
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_stage_all
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_unstage
import dev.ide.vcs.ui.generated.resources.vcs_tooltip_unstage_all
import dev.ide.vcs.ui.generated.resources.vcs_abort_merge
import dev.ide.vcs.ui.generated.resources.vcs_bisect_in_progress
import dev.ide.vcs.ui.generated.resources.vcs_cherry_pick_in_progress
import dev.ide.vcs.ui.generated.resources.vcs_revert_in_progress
import dev.ide.vcs.ui.generated.resources.vcs_title
import dev.ide.vcs.ui.generated.resources.vcs_add_ignores
import dev.ide.vcs.ui.generated.resources.vcs_amend
import dev.ide.vcs.ui.generated.resources.vcs_branch
import dev.ide.vcs.ui.generated.resources.vcs_cancel
import dev.ide.vcs.ui.generated.resources.vcs_clean_body
import dev.ide.vcs.ui.generated.resources.vcs_clean_title
import dev.ide.vcs.ui.generated.resources.vcs_clone
import dev.ide.vcs.ui.generated.resources.vcs_commit
import dev.ide.vcs.ui.generated.resources.vcs_commit_and_push
import dev.ide.vcs.ui.generated.resources.vcs_commit_hint
import dev.ide.vcs.ui.generated.resources.vcs_detached
import dev.ide.vcs.ui.generated.resources.vcs_discard
import dev.ide.vcs.ui.generated.resources.vcs_discard_body
import dev.ide.vcs.ui.generated.resources.vcs_discard_title
import dev.ide.vcs.ui.generated.resources.vcs_fetch
import dev.ide.vcs.ui.generated.resources.vcs_github
import dev.ide.vcs.ui.generated.resources.vcs_history
import dev.ide.vcs.ui.generated.resources.vcs_init
import dev.ide.vcs.ui.generated.resources.vcs_mark_resolved
import dev.ide.vcs.ui.generated.resources.vcs_merge_in_progress
import dev.ide.vcs.ui.generated.resources.vcs_no_commits
import dev.ide.vcs.ui.generated.resources.vcs_no_project
import dev.ide.vcs.ui.generated.resources.vcs_not_a_repo_body
import dev.ide.vcs.ui.generated.resources.vcs_not_a_repo_title
import dev.ide.vcs.ui.generated.resources.vcs_nothing_staged
import dev.ide.vcs.ui.generated.resources.vcs_pull
import dev.ide.vcs.ui.generated.resources.vcs_push
import dev.ide.vcs.ui.generated.resources.vcs_rebase_in_progress
import dev.ide.vcs.ui.generated.resources.vcs_refresh
import dev.ide.vcs.ui.generated.resources.vcs_section_changes
import dev.ide.vcs.ui.generated.resources.vcs_section_conflicts
import dev.ide.vcs.ui.generated.resources.vcs_section_staged
import dev.ide.vcs.ui.generated.resources.vcs_stage
import dev.ide.vcs.ui.generated.resources.vcs_stage_all
import dev.ide.vcs.ui.generated.resources.vcs_stashes
import dev.ide.vcs.ui.generated.resources.vcs_unstage
import dev.ide.vcs.ui.generated.resources.vcs_unstage_all
import dev.ide.vcs.ui.generated.resources.vcs_up_to_date
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The Git tool window: the branch and sync header, the working-tree changes grouped by conflicts, staged, and
 * unstaged, and the commit box. Everything that needs room (branches, history, a diff, sign-in, clone,
 * GitHub) is a contributed screen this panel navigates to, since the panel itself is a narrow sidebar.
 */
@Composable
internal fun GitPanel(ctx: ToolWindowContext) {
    val vcs = ctx.backend.vcs
    val status by vcs.status.collectAsState()
    val activity by vcs.activity.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()
    val hasProject = ctx.backend.project.rootPath.isNotBlank()

    // The panel is the first thing to show a project's Git state, and it may be opened long after the last
    // file-system change, so re-read on entry rather than trusting the cached snapshot.
    LaunchedEffect(ctx.backend, hasProject) { vcs.refresh() }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            if (result.message.isNotBlank()) feedback.show(result.message, isError = !result.ok)
            if (result.authRequired) ctx.openScreen(VcsService.SCREEN_ACCOUNTS)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PanelHeader(
            status = status,
            enabled = status.present,
            onRefresh = { scope.launch { vcs.refresh() } },
            onBranches = { ctx.openScreen(VcsService.SCREEN_BRANCHES) },
            onHistory = {
                VcsNav.historyPath = null
                ctx.openScreen(VcsService.SCREEN_HISTORY)
            },
            onGitHub = { ctx.openScreen(VcsService.SCREEN_GITHUB) },
            onStashes = { ctx.openScreen(VcsService.SCREEN_STASHES) },
            onFetch = { perform { vcs.fetch() } },
            onIgnores = { perform { vcs.addDefaultIgnores() } },
            onAbortMerge = if (status.operation == UiVcsStatus.OP_MERGE) {
                { perform { vcs.abortMerge() } }
            } else null,
        )
        if (activity.busy) ActivityRow(activity.task, activity.fraction)
        FeedbackStrip(feedback)
        // A failed status read is reported in place: an empty change list would otherwise read as "clean".
        if (status.error.isNotBlank()) {
            Text(
                status.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        when {
            !hasProject -> VcsEmptyState(
                icon = CaIcons.gitBranch,
                title = stringResource(Res.string.vcs_title),
                body = stringResource(Res.string.vcs_no_project),
            )

            !status.present -> NoRepositoryState(
                onInit = { perform { vcs.initRepository() } },
                onClone = { ctx.openScreen(VcsService.SCREEN_CLONE) },
            )

            else -> WorkingCopy(ctx, status, feedback, ::perform)
        }
    }
}

// ---- header ------------------------------------------------------------------------------------

@Composable
private fun PanelHeader(
    status: UiVcsStatus,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onBranches: () -> Unit,
    onHistory: () -> Unit,
    onGitHub: () -> Unit,
    onStashes: () -> Unit,
    onFetch: () -> Unit,
    onIgnores: () -> Unit,
    onAbortMerge: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BranchChip(status, Modifier.weight(1f), enabled = enabled, onClick = onBranches)
            Spacer(Modifier.width(4.dp))
            VcsOverflowMenu(stringResource(Res.string.vcs_menu_more)) {
                heading(stringResource(Res.string.vcs_menu_repository))
                item(
                    stringResource(Res.string.vcs_history),
                    CaIcons.gitCommit,
                    stringResource(Res.string.vcs_tooltip_history),
                    enabled = enabled,
                    onClick = onHistory,
                )
                item(
                    stringResource(Res.string.vcs_stashes),
                    CaIcons.stash,
                    stringResource(Res.string.vcs_stash_changes),
                    enabled = enabled,
                    onClick = onStashes,
                )
                item(
                    stringResource(Res.string.vcs_github),
                    CaIcons.account,
                    stringResource(Res.string.vcs_tooltip_github),
                    onClick = onGitHub,
                )
                separator()
                heading(stringResource(Res.string.vcs_menu_sync))
                item(
                    stringResource(Res.string.vcs_fetch),
                    CaIcons.refresh,
                    stringResource(Res.string.vcs_tooltip_fetch),
                    enabled = enabled,
                    onClick = onFetch,
                )
                item(
                    stringResource(Res.string.vcs_refresh),
                    CaIcons.refresh,
                    stringResource(Res.string.vcs_tooltip_refresh),
                    enabled = enabled,
                    onClick = onRefresh,
                )
                separator()
                item(
                    stringResource(Res.string.vcs_add_ignores),
                    CaIcons.docText,
                    enabled = enabled,
                    onClick = onIgnores,
                )
                if (onAbortMerge != null) {
                    item(
                        stringResource(Res.string.vcs_abort_merge),
                        CaIcons.close,
                        danger = true,
                        onClick = onAbortMerge,
                    )
                }
            }
        }
        if (status.operation != UiVcsStatus.OP_NONE) {
            Spacer(Modifier.height(6.dp))
            Text(
                operationLabel(status.operation),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.error,
                modifier = Modifier
                    .background(scheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BranchChip(status: UiVcsStatus, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = when {
        !status.present -> stringResource(Res.string.vcs_branch)
        status.detached -> stringResource(Res.string.vcs_detached)
        status.branch.isNotBlank() -> status.branch
        status.unborn -> stringResource(Res.string.vcs_no_commits)
        else -> stringResource(Res.string.vcs_branch)
    }
    val chip = @Composable {
        Row(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceContainerHigh)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CaIcons.gitBranch, null, Modifier.size(16.dp), tint = scheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                // The word "Branch" above the name: without it the chip is just a string with a leading glyph,
                // and a branch called `main` reads as a title rather than as something switchable.
                Text(
                    stringResource(Res.string.vcs_branch),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (enabled) {
                Spacer(Modifier.width(4.dp))
                Icon(CaIcons.chevronDown, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
    if (enabled) WithTooltip(stringResource(Res.string.vcs_tooltip_branch, label)) { chip() } else chip()
}

/**
 * Pull and push, named rather than drawn, with the counts that make them meaningful. This is the row a
 * newcomer most needs to read: two cloud arrows alone say nothing about which way the work is moving.
 */
@Composable
private fun SyncBar(
    status: UiVcsStatus,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (status.upstream.isBlank()) {
            // Nothing to pull from or push to yet, so say that instead of offering two dead buttons.
            Text(
                stringResource(Res.string.vcs_no_remote_short),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                modifier = Modifier.weight(1f),
            )
            VcsLabelledButton(
                icon = CaIcons.account,
                label = stringResource(Res.string.vcs_set_up_remote),
                tooltip = stringResource(Res.string.vcs_tooltip_github),
                onClick = onConnect,
            )
            return@Row
        }
        VcsLabelledButton(
            icon = CaIcons.cloudDownload,
            label = stringResource(Res.string.vcs_pull),
            tooltip = stringResource(Res.string.vcs_tooltip_pull),
            onClick = onPull,
            count = status.behind,
            emphasised = status.behind > 0,
        )
        VcsLabelledButton(
            icon = CaIcons.cloudUpload,
            label = stringResource(Res.string.vcs_push),
            tooltip = stringResource(Res.string.vcs_tooltip_push),
            onClick = onPush,
            count = status.ahead,
            emphasised = status.ahead > 0,
        )
        Spacer(Modifier.weight(1f))
        if (status.ahead == 0 && status.behind == 0) {
            Text(
                stringResource(Res.string.vcs_up_to_date),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
            )
        }
    }
}

@Composable
private fun ActivityRow(task: String, fraction: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            task,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        if (fraction >= 0f) {
            LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().height(3.dp))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun operationLabel(operation: String): String = when (operation) {
    UiVcsStatus.OP_MERGE -> stringResource(Res.string.vcs_merge_in_progress)
    UiVcsStatus.OP_REBASE -> stringResource(Res.string.vcs_rebase_in_progress)
    UiVcsStatus.OP_CHERRY_PICK -> stringResource(Res.string.vcs_cherry_pick_in_progress)
    UiVcsStatus.OP_REVERT -> stringResource(Res.string.vcs_revert_in_progress)
    UiVcsStatus.OP_BISECT -> stringResource(Res.string.vcs_bisect_in_progress)
    else -> ""
}

// ---- states ------------------------------------------------------------------------------------

@Composable
private fun NoRepositoryState(onInit: () -> Unit, onClone: () -> Unit) {
    VcsEmptyState(
        icon = CaIcons.gitBranch,
        title = stringResource(Res.string.vcs_not_a_repo_title),
        body = stringResource(Res.string.vcs_not_a_repo_body),
    ) {
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(Res.string.vcs_init), onInit, icon = CaIcons.plus)
        Text(
            stringResource(Res.string.vcs_clone),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClone)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ---- working copy ------------------------------------------------------------------------------

@Composable
private fun WorkingCopy(
    ctx: ToolWindowContext,
    status: UiVcsStatus,
    feedback: VcsFeedback,
    perform: (suspend () -> UiVcsResult) -> Unit,
) {
    val vcs = ctx.backend.vcs
    var message by remember(ctx.backend) { mutableStateOf("") }
    var amend by remember(ctx.backend) { mutableStateOf(false) }
    var pushAfter by remember(ctx.backend) { mutableStateOf(true) }
    var discarding by remember { mutableStateOf<UiVcsChange?>(null) }

    Column(Modifier.fillMaxSize()) {
        SyncBar(
            status = status,
            onPull = { perform { vcs.pull() } },
            onPush = { perform { vcs.push() } },
            onConnect = { ctx.openScreen(VcsService.SCREEN_GITHUB) },
        )

        Box(Modifier.weight(1f)) {
            if (status.clean) {
                VcsEmptyState(
                    icon = CaIcons.check,
                    title = stringResource(Res.string.vcs_clean_title),
                    body = stringResource(Res.string.vcs_clean_body),
                ) {
                    if (status.headSummary.isNotBlank()) {
                        Text(
                            "${status.headShortId} ${status.headSummary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                ChangeList(
                    status = status,
                    onOpenDiff = { change ->
                        VcsNav.diff = DiffTarget(path = change.path, staged = change.staged)
                        ctx.openScreen(VcsService.SCREEN_DIFF)
                    },
                    onOpenHistory = { change ->
                        VcsNav.historyPath = change.path
                        ctx.openScreen(VcsService.SCREEN_HISTORY)
                    },
                    onStage = { paths -> perform { vcs.stage(paths) } },
                    onUnstage = { paths -> perform { vcs.unstage(paths) } },
                    onResolve = { paths -> perform { vcs.markResolved(paths) } },
                    onDiscard = { change -> discarding = change },
                )
            }
        }

        CommitBox(
            message = message,
            onMessage = { message = it },
            amend = amend,
            onAmend = { amend = it },
            pushAfter = pushAfter,
            onPushAfter = { pushAfter = it },
            canCommit = status.staged.isNotEmpty() || amend,
            hasRemote = status.upstream.isNotBlank(),
            onCommit = {
                val text = message
                val alsoPush = pushAfter && status.upstream.isNotBlank()
                perform {
                    val committed = vcs.commit(text, amend)
                    if (!committed.ok) return@perform committed
                    message = ""
                    amend = false
                    if (alsoPush) vcs.push() else committed
                }
            },
        )
    }

    val pending = discarding
    CenteredDialog(visible = pending != null, onDismiss = { discarding = null }) {
        if (pending != null) {
            ConfirmCard(
                title = stringResource(Res.string.vcs_discard_title),
                body = stringResource(Res.string.vcs_discard_body, pending.name),
                confirmLabel = stringResource(Res.string.vcs_discard),
                onConfirm = {
                    discarding = null
                    perform { vcs.discard(listOf(pending.path)) }
                },
                onCancel = { discarding = null },
            )
        }
    }
}

@Composable
private fun ChangeList(
    status: UiVcsStatus,
    onOpenDiff: (UiVcsChange) -> Unit,
    onOpenHistory: (UiVcsChange) -> Unit,
    onStage: (List<String>) -> Unit,
    onUnstage: (List<String>) -> Unit,
    onResolve: (List<String>) -> Unit,
    onDiscard: (UiVcsChange) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (status.conflicted.isNotEmpty()) {
            item("conflicts") {
                SectionHeader(
                    title = stringResource(Res.string.vcs_section_conflicts),
                    count = status.conflicted.size,
                    actionLabel = stringResource(Res.string.vcs_mark_resolved),
                    onAction = { onResolve(status.conflicted.map { it.path }) },
                )
                SectionHint(stringResource(Res.string.vcs_hint_conflicts))
            }
            items(status.conflicted, key = { "c:${it.path}" }) { change ->
                ChangeRow(
                    change = change,
                    onClick = { onOpenDiff(change) },
                    primaryIcon = CaIcons.check,
                    primaryLabel = stringResource(Res.string.vcs_mark_resolved),
                    primaryTooltip = stringResource(Res.string.vcs_tooltip_resolved),
                    onPrimary = { onResolve(listOf(change.path)) },
                    onOpenDiff = { onOpenDiff(change) },
                    onOpenHistory = { onOpenHistory(change) },
                    onDiscard = { onDiscard(change) },
                )
            }
        }
        if (status.staged.isNotEmpty()) {
            item("staged") {
                SectionHeader(
                    title = stringResource(Res.string.vcs_section_staged),
                    count = status.staged.size,
                    actionLabel = stringResource(Res.string.vcs_unstage_all),
                    actionTooltip = stringResource(Res.string.vcs_tooltip_unstage_all),
                    onAction = { onUnstage(status.staged.map { it.path }) },
                )
                SectionHint(stringResource(Res.string.vcs_hint_staged))
            }
            items(status.staged, key = { "s:${it.path}" }) { change ->
                ChangeRow(
                    change = change,
                    onClick = { onOpenDiff(change) },
                    // A minus reads as "take it back out"; the close glyph this used to carry read as "delete".
                    primaryIcon = CaIcons.minus,
                    primaryLabel = stringResource(Res.string.vcs_unstage),
                    primaryTooltip = stringResource(Res.string.vcs_tooltip_unstage),
                    onPrimary = { onUnstage(listOf(change.path)) },
                    onOpenDiff = { onOpenDiff(change) },
                    onOpenHistory = { onOpenHistory(change) },
                    onDiscard = null,
                )
            }
        }
        if (status.unstaged.isNotEmpty()) {
            item("unstaged") {
                SectionHeader(
                    title = stringResource(Res.string.vcs_section_changes),
                    count = status.unstaged.size,
                    actionLabel = stringResource(Res.string.vcs_stage_all),
                    actionTooltip = stringResource(Res.string.vcs_tooltip_stage_all),
                    onAction = { onStage(status.unstaged.map { it.path }) },
                )
                SectionHint(stringResource(Res.string.vcs_hint_changes))
            }
            items(status.unstaged, key = { "u:${it.path}" }) { change ->
                ChangeRow(
                    change = change,
                    onClick = { onOpenDiff(change) },
                    primaryIcon = CaIcons.plus,
                    primaryLabel = stringResource(Res.string.vcs_stage),
                    primaryTooltip = stringResource(Res.string.vcs_tooltip_stage),
                    onPrimary = { onStage(listOf(change.path)) },
                    onOpenDiff = { onOpenDiff(change) },
                    onOpenHistory = { onOpenHistory(change) },
                    onDiscard = { onDiscard(change) },
                )
            }
        }
        item("tail") { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * One changed file. The row carries a single icon, the one action used on nearly every row, and everything
 * else is a worded menu: discarding your work and opening a diff should not be two similar glyphs sitting
 * next to each other.
 */
@Composable
private fun ChangeRow(
    change: UiVcsChange,
    onClick: () -> Unit,
    primaryIcon: ImageVector,
    primaryLabel: String,
    primaryTooltip: String,
    onPrimary: () -> Unit,
    onOpenDiff: () -> Unit,
    onOpenHistory: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WithTooltip(statusLabel(change.status)) { StatusMark(change.status) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                change.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (change.status == UiVcsChange.STATUS_DELETED) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (change.directory.isNotBlank()) {
                Text(
                    change.directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        VcsIconButton(primaryIcon, primaryLabel, onPrimary, iconSize = 16, boxSize = 30, tint = scheme.primary)
        VcsOverflowMenu(stringResource(Res.string.vcs_menu_more)) {
            heading(stringResource(Res.string.vcs_menu_file))
            item(stringResource(Res.string.vcs_menu_view_changes), CaIcons.code, onClick = onOpenDiff)
            item(stringResource(Res.string.vcs_menu_file_history), CaIcons.gitCommit, onClick = onOpenHistory)
            item(primaryLabel, primaryIcon, primaryTooltip, onClick = onPrimary)
            if (onDiscard != null) {
                separator()
                item(
                    stringResource(Res.string.vcs_discard),
                    CaIcons.undo,
                    stringResource(Res.string.vcs_tooltip_discard),
                    danger = true,
                    onClick = onDiscard,
                )
            }
        }
    }
}

@Composable
private fun CommitBox(
    message: String,
    onMessage: (String) -> Unit,
    amend: Boolean,
    onAmend: (Boolean) -> Unit,
    pushAfter: Boolean,
    onPushAfter: (Boolean) -> Unit,
    canCommit: Boolean,
    hasRemote: Boolean,
    onCommit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VcsField(
            value = message,
            onValueChange = onMessage,
            placeholder = stringResource(Res.string.vcs_commit_hint),
            singleLine = false,
            minHeight = 62,
        )
        VcsCheckRow(stringResource(Res.string.vcs_amend), amend, onToggle = { onAmend(!amend) })
        if (hasRemote) {
            VcsCheckRow(
                label = stringResource(Res.string.vcs_push_after_commit),
                checked = pushAfter,
                detail = stringResource(Res.string.vcs_hint_push),
                onToggle = { onPushAfter(!pushAfter) },
            )
        }
        if (canCommit) {
            // One button, and its label says exactly what the checkbox above turned it into.
            PrimaryButton(
                text = if (pushAfter && hasRemote) {
                    stringResource(Res.string.vcs_commit_and_push)
                } else {
                    stringResource(Res.string.vcs_commit)
                },
                onClick = onCommit,
                modifier = Modifier.fillMaxWidth(),
                icon = if (pushAfter && hasRemote) CaIcons.cloudUpload else CaIcons.check,
            )
        } else {
            Text(
                stringResource(Res.string.vcs_nothing_staged),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
            )
        }
        Text(
            stringResource(Res.string.vcs_hint_commit),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline,
        )
    }
}

/** The card body of a destructive confirmation, hosted by [CenteredDialog]. */
@Composable
internal fun ConfirmCard(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .widthIn(max = 340.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                stringResource(Res.string.vcs_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                confirmLabel,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
