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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiVcsCommit
import dev.ide.ui.backend.UiVcsCommitDetail
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_commit_files
import dev.ide.vcs.ui.generated.resources.vcs_deletions
import dev.ide.vcs.ui.generated.resources.vcs_history
import dev.ide.vcs.ui.generated.resources.vcs_history_empty
import dev.ide.vcs.ui.generated.resources.vcs_insertions
import dev.ide.vcs.ui.generated.resources.vcs_load_more
import dev.ide.vcs.ui.generated.resources.vcs_merge_commit
import org.jetbrains.compose.resources.stringResource

/** How many commits one page of history loads. */
private const val PAGE = 40

/**
 * The commit log, newest first. Tapping a commit expands it in place with the paths it touched and the line
 * totals; tapping one of those paths opens the diff of that commit against its first parent.
 */
@Composable
internal fun HistoryScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val path = remember { VcsNav.historyPath }

    var commits by remember { mutableStateOf(emptyList<UiVcsCommit>()) }
    var loadedPages by remember { mutableStateOf(1) }
    var exhausted by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<UiVcsCommitDetail?>(null) }

    LaunchedEffect(loadedPages, path) {
        val loaded = vcs.log(limit = PAGE * loadedPages, skip = 0, path = path)
        exhausted = loaded.size < PAGE * loadedPages
        commits = loaded
    }
    LaunchedEffect(expanded) {
        detail = expanded?.let { vcs.commitDetail(it) }
    }

    ExpressiveScaffold(
        title = stringResource(Res.string.vcs_history),
        onBack = ctx::back,
        large = false,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (commits.isEmpty()) {
                VcsEmptyState(
                    icon = CaIcons.gitCommit,
                    title = stringResource(Res.string.vcs_history),
                    body = stringResource(Res.string.vcs_history_empty),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (path != null) {
                        item("path") {
                            Text(
                                path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(commits, key = { it.id }) { commit ->
                        CommitRow(
                            commit = commit,
                            expanded = expanded == commit.id,
                            detail = detail?.takeIf { it.commit.id == commit.id },
                            onToggle = { expanded = if (expanded == commit.id) null else commit.id },
                            onOpenFile = { filePath ->
                                VcsNav.diff = DiffTarget(
                                    path = filePath,
                                    commitId = commit.id,
                                    commitLabel = "${commit.shortId} ${commit.summary}",
                                )
                                ctx.openScreen(VcsService.SCREEN_DIFF)
                            },
                        )
                    }
                    if (!exhausted) {
                        item("more") {
                            Text(
                                stringResource(Res.string.vcs_load_more),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { loadedPages++ }
                                    .padding(vertical = 14.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item("tail") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(
    commit: UiVcsCommit,
    expanded: Boolean,
    detail: UiVcsCommitDetail?,
    onToggle: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthorAvatar(commit.authorName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    commit.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        commit.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(commit.shortId, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                    if (commit.timeLabel.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(commit.timeLabel, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                    }
                }
                if (commit.refs.isNotEmpty() || commit.merge) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (commit.merge) Chip(stringResource(Res.string.vcs_merge_commit))
                        commit.refs.take(3).forEach { ref ->
                            Chip(
                                ref,
                                fill = scheme.primaryContainer,
                                textColor = scheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            Icon(
                if (expanded) CaIcons.chevronUp else CaIcons.chevronDown,
                null,
                Modifier.size(16.dp),
                tint = scheme.outline,
            )
        }
        if (expanded) CommitDetail(detail, onOpenFile)
    }
}

@Composable
private fun CommitDetail(detail: UiVcsCommitDetail?, onOpenFile: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (detail == null) {
        Spacer(Modifier.height(8.dp))
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .background(scheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (detail.commit.body.isNotBlank()) {
            Text(detail.commit.body, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.vcs_commit_files, detail.files.size),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (detail.insertions > 0) {
                Text(
                    stringResource(Res.string.vcs_insertions, detail.insertions),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ide.colors.gitAdded,
                )
            }
            if (detail.deletions > 0) {
                Text(
                    stringResource(Res.string.vcs_deletions, detail.deletions),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ide.colors.gitDeleted,
                )
            }
        }
        detail.files.forEach { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenFile(file.path) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusMark(file.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    file.path,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A circular initial for the commit author, since no avatar image is fetched for a Git identity. */
@Composable
private fun AuthorAvatar(name: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(30.dp).background(scheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
