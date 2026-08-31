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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.ide.ui.backend.UiVcsBranch
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_checkout_branch
import dev.ide.vcs.ui.generated.resources.vcs_delete_branch
import dev.ide.vcs.ui.generated.resources.vcs_menu_more
import dev.ide.vcs.ui.generated.resources.vcs_merge_into_current
import dev.ide.vcs.ui.generated.resources.vcs_branches
import dev.ide.vcs.ui.generated.resources.vcs_cancel
import dev.ide.vcs.ui.generated.resources.vcs_branches_local
import dev.ide.vcs.ui.generated.resources.vcs_branches_none
import dev.ide.vcs.ui.generated.resources.vcs_branches_remote
import dev.ide.vcs.ui.generated.resources.vcs_branches_search
import dev.ide.vcs.ui.generated.resources.vcs_create
import dev.ide.vcs.ui.generated.resources.vcs_current_branch
import dev.ide.vcs.ui.generated.resources.vcs_delete
import dev.ide.vcs.ui.generated.resources.vcs_delete_branch_body
import dev.ide.vcs.ui.generated.resources.vcs_delete_branch_title
import dev.ide.vcs.ui.generated.resources.vcs_force_delete
import dev.ide.vcs.ui.generated.resources.vcs_merge_into
import dev.ide.vcs.ui.generated.resources.vcs_new_branch
import dev.ide.vcs.ui.generated.resources.vcs_new_branch_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The branch list: search, create, switch, merge into the current branch, and delete. A remote-tracking entry
 * checks out as a new local branch that follows it, which is what the engine does for a `remote/name`.
 */
@Composable
internal fun BranchesScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val status by vcs.status.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()

    var branches by remember { mutableStateOf(emptyList<UiVcsBranch>()) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<UiVcsBranch?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload, status.branch) { branches = vcs.branches(includeRemote = true) }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            if (result.message.isNotBlank()) feedback.show(result.message, isError = !result.ok)
            reload++
        }
    }

    val filtered = remember(branches, query) {
        if (query.isBlank()) branches else branches.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val local = filtered.filter { !it.remote }
    val remote = filtered.filter { it.remote }
    val current = status.branch

    ExpressiveScaffold(
        title = stringResource(Res.string.vcs_branches),
        onBack = ctx::back,
        actions = {
            VcsIconButton(CaIcons.plus, stringResource(Res.string.vcs_new_branch), { creating = true })
        },
        large = false,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            VcsField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(Res.string.vcs_branches_search),
                leading = CaIcons.search,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FeedbackStrip(feedback)
            if (filtered.isEmpty()) {
                VcsEmptyState(
                    icon = CaIcons.gitBranch,
                    title = stringResource(Res.string.vcs_branches),
                    body = stringResource(Res.string.vcs_branches_none),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (local.isNotEmpty()) {
                        item("local") {
                            SectionHeader(stringResource(Res.string.vcs_branches_local), local.size)
                        }
                        items(local, key = { "l:${it.name}" }) { branch ->
                            BranchRow(
                                branch = branch,
                                currentBranch = current,
                                onCheckout = { perform { vcs.checkoutBranch(branch.name) } },
                                onMerge = { perform { vcs.mergeBranch(branch.name) } },
                                onDelete = { deleting = branch },
                            )
                        }
                    }
                    if (remote.isNotEmpty()) {
                        item("remote") {
                            SectionHeader(stringResource(Res.string.vcs_branches_remote), remote.size)
                        }
                        items(remote, key = { "r:${it.name}" }) { branch ->
                            BranchRow(
                                branch = branch,
                                currentBranch = current,
                                onCheckout = { perform { vcs.checkoutBranch(branch.name) } },
                                onMerge = { perform { vcs.mergeBranch(branch.name) } },
                                onDelete = null,
                            )
                        }
                    }
                    item("tail") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    CenteredDialog(visible = creating, onDismiss = { creating = false }) {
        NewBranchCard(
            name = newName,
            onName = { newName = it },
            startPoint = current,
            onCancel = { creating = false; newName = "" },
            onCreate = {
                val name = newName
                creating = false
                newName = ""
                perform { vcs.createBranch(name, startPoint = null, checkout = true) }
            },
        )
    }

    val pending = deleting
    CenteredDialog(visible = pending != null, onDismiss = { deleting = null }) {
        if (pending != null) {
            ConfirmCard(
                title = stringResource(Res.string.vcs_delete_branch_title, pending.name),
                body = stringResource(Res.string.vcs_delete_branch_body),
                confirmLabel = stringResource(Res.string.vcs_force_delete),
                onConfirm = {
                    deleting = null
                    perform { vcs.deleteBranch(pending.name, force = true) }
                },
                onCancel = { deleting = null },
            )
        }
    }
}

@Composable
private fun BranchRow(
    branch: UiVcsBranch,
    currentBranch: String,
    onCheckout: () -> Unit,
    onMerge: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = !branch.current, onClick = onCheckout)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (branch.current) CaIcons.check else CaIcons.gitBranch,
            null,
            Modifier.size(18.dp),
            tint = if (branch.current) scheme.primary else scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                branch.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = if (branch.current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                branch.current -> stringResource(Res.string.vcs_current_branch)
                branch.upstream.isNotBlank() -> branch.upstream
                else -> branch.shortId
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
            }
        }
        // A merge glyph and a close glyph side by side is exactly the pair a newcomer misreads, so both are
        // worded rows instead. Switching branches stays the row tap, which is what the list is for.
        if (!branch.current) {
            VcsOverflowMenu(stringResource(Res.string.vcs_menu_more)) {
                item(stringResource(Res.string.vcs_checkout_branch), CaIcons.check, onClick = onCheckout)
                if (currentBranch.isNotBlank()) {
                    item(
                        stringResource(Res.string.vcs_merge_into, currentBranch),
                        CaIcons.gitMerge,
                        stringResource(Res.string.vcs_merge_into_current),
                        onClick = onMerge,
                    )
                }
                if (onDelete != null) {
                    separator()
                    item(
                        stringResource(Res.string.vcs_delete_branch),
                        CaIcons.close,
                        danger = true,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBranchCard(
    name: String,
    onName: (String) -> Unit,
    startPoint: String,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .widthIn(max = 360.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.vcs_new_branch),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        VcsField(
            value = name,
            onValueChange = onName,
            placeholder = stringResource(Res.string.vcs_new_branch_hint),
            leading = CaIcons.gitBranch,
        )
        if (startPoint.isNotBlank()) {
            Text(startPoint, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.vcs_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            PrimaryButton(stringResource(Res.string.vcs_create), onCreate)
        }
    }
}
