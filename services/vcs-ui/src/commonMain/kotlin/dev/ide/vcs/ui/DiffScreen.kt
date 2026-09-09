package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiVcsDiff
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_deletions
import dev.ide.vcs.ui.generated.resources.vcs_diff
import dev.ide.vcs.ui.generated.resources.vcs_diff_binary
import dev.ide.vcs.ui.generated.resources.vcs_diff_empty
import dev.ide.vcs.ui.generated.resources.vcs_insertions
import dev.ide.vcs.ui.generated.resources.vcs_file_history
import org.jetbrains.compose.resources.stringResource

/**
 * A unified diff, rendered line by line. The engine already produces the patch text, so this only classifies
 * each line and paints it: additions and deletions in the palette's Git colours, hunk headers as a divider,
 * and everything else as plain code.
 */
@Composable
internal fun DiffScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val target = remember { VcsNav.diff }
    var diff by remember { mutableStateOf<UiVcsDiff?>(null) }

    LaunchedEffect(target) {
        diff = target?.let { vcs.diff(it.path, staged = it.staged, commitId = it.commitId) }
    }

    val title = target?.path?.substringAfterLast('/') ?: stringResource(Res.string.vcs_diff)
    ExpressiveScaffold(
        title = title,
        onBack = ctx::back,
        actions = {
            if (target != null) {
                VcsIconButton(
                    CaIcons.gitCommit,
                    stringResource(Res.string.vcs_file_history),
                    {
                        VcsNav.historyPath = target.path
                        ctx.openScreen(VcsService.SCREEN_HISTORY)
                    },
                )
            }
        },
        large = false,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DiffHeader(target, diff)
            val text = diff?.text.orEmpty()
            when {
                diff?.binary == true -> VcsEmptyState(
                    icon = CaIcons.image,
                    title = title,
                    body = stringResource(Res.string.vcs_diff_binary),
                )

                text.isBlank() -> VcsEmptyState(
                    icon = CaIcons.code,
                    title = title,
                    body = stringResource(Res.string.vcs_diff_empty),
                )

                else -> DiffBody(text)
            }
        }
    }
}

@Composable
private fun DiffHeader(target: DiffTarget?, diff: UiVcsDiff?) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (target != null) {
            Text(
                target.path,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (target?.commitLabel?.isNotBlank() == true) {
            Text(
                target.commitLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (diff != null && !diff.binary && (diff.insertions > 0 || diff.deletions > 0)) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (diff.insertions > 0) {
                    Text(
                        stringResource(Res.string.vcs_insertions, diff.insertions),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ide.colors.gitAdded,
                    )
                }
                if (diff.deletions > 0) {
                    Text(
                        stringResource(Res.string.vcs_deletions, diff.deletions),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ide.colors.gitDeleted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffBody(text: String) {
    val lines = remember(text) { text.split('\n') }
    val horizontal = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val added = Ide.colors.gitAdded
    val deleted = Ide.colors.gitDeleted

    Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                val kind = classify(line, index)
                val background = when (kind) {
                    LineKind.ADDED -> added.copy(alpha = 0.14f)
                    LineKind.DELETED -> deleted.copy(alpha = 0.14f)
                    LineKind.HUNK -> scheme.surfaceContainerHigh
                    else -> Color.Transparent
                }
                val color = when (kind) {
                    LineKind.ADDED -> added
                    LineKind.DELETED -> deleted
                    LineKind.HUNK -> scheme.primary
                    LineKind.HEADER -> scheme.onSurfaceVariant
                    LineKind.CONTEXT -> scheme.onSurface
                }
                Text(
                    text = line.ifEmpty { " " },
                    style = Ca.type.codeSmall,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .horizontalScroll(horizontal)
                        .padding(horizontal = 12.dp, vertical = 1.dp),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private enum class LineKind { HEADER, HUNK, ADDED, DELETED, CONTEXT }

/**
 * Classify one line of unified-diff text. The leading `+++`/`---` file lines come first and would otherwise
 * read as an addition and a deletion, so the first few lines are treated as header regardless.
 */
private fun classify(line: String, index: Int): LineKind = when {
    line.startsWith("diff ") || line.startsWith("index ") ||
        line.startsWith("+++") || line.startsWith("---") ||
        line.startsWith("new file") || line.startsWith("deleted file") ||
        line.startsWith("similarity index") || line.startsWith("rename ") -> LineKind.HEADER

    line.startsWith("@@") -> LineKind.HUNK
    line.startsWith("+") -> LineKind.ADDED
    line.startsWith("-") -> LineKind.DELETED
    index == 0 -> LineKind.HEADER
    else -> LineKind.CONTEXT
}
