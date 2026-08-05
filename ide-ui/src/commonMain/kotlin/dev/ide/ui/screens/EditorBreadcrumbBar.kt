package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.LeftPanelId
import dev.ide.ui.OpenFile
import dev.ide.ui.components.Breadcrumb
import dev.ide.ui.editor.preview.isLayoutPreviewable
import dev.ide.ui.editor.preview.isMarkdownPreviewable
import dev.ide.ui.editor.preview.isPreviewable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.breadcrumb_blocks
import dev.ide.ui.generated.resources.breadcrumb_code
import dev.ide.ui.generated.resources.breadcrumb_preview
import dev.ide.ui.generated.resources.breadcrumb_split
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The breadcrumb row: the caret-tracking location line on the left and the view-mode switch pinned to the
 * right (IntelliJ-style). The breadcrumb tracks the caret (module › enclosing type(s) › method, debounced
 * via a reparse, falling back to the file path outside any declaration). The edit actions (undo/redo/find)
 * live in the editor's top bar beside Run, so this row reads as just location + view.
 */
@Composable
internal fun BreadcrumbBar(
    state: IdeUiState,
    active: OpenFile,
    hasPreview: Boolean,
) {
    var crumbs by remember(active.path) { mutableStateOf(breadcrumbFor(state, active)) }
    val caretOffset = active.session.selection.start
    LaunchedEffect(active.path, active.session.textRevision, caretOffset) {
        delay(200.milliseconds)
        val structure = runCatching {
            state.backend.editor.breadcrumbAt(
                active.path,
                active.text,
                caretOffset
            )
        }.getOrDefault(emptyList())
        crumbs = if (structure.isEmpty()) breadcrumbFor(state, active)
        else listOfNotNull(state.backend.files.moduleNameForFile(active.path)) + structure
    }
    val canPreview = isPreviewable(active.path) || isLayoutPreviewable(active.path) ||
            isMarkdownPreviewable(active.path) || hasPreview
    Row(
        Modifier.fillMaxWidth()
            .background(Ide.colors.editorBg)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tapping the location line opens the Structure panel in the left sidebar (IntelliJ-style outline).
        Box(Modifier.weight(1f).clickable { state.selectLeftPanel(LeftPanelId.STRUCTURE) }) { Breadcrumb(crumbs) }
        ViewModeToggle(active.viewMode, canPreview, state.blocksEnabled) { active.viewMode = it }
    }
}

/** Shared height for the editor view-mode toggle pill. */
private val EditorToolbarHeight = 34.dp

/**
 * The `Code / Blocks / Preview / Split` segmented control switching the active tab between editor surfaces.
 * Icon-only segments (the labels survive as content descriptions) so it stays narrow on every width.
 */
@Composable
private fun ViewModeToggle(
    mode: EditorViewMode,
    canPreview: Boolean,
    blocksEnabled: Boolean,
    onSelect: (EditorViewMode) -> Unit
) {
    Row(
        Modifier.height(EditorToolbarHeight)
            // Edge-to-edge (enableEdgeToEdge + a safeDrawing root inset, which does NOT cover systemGestures)
            // anchors this control against the right screen edge, where the system back-gesture zone consumes
            // the down event on the last segment (Split) before its clickable ever sees it — so Split can't be
            // selected while Preview, one segment further in, still works. Inset the pill clear of the end
            // gesture inset so every segment stays tappable (empty on desktop / 3-button nav → a no-op there).
            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.End))
            .background(
                Ide.colors.editorBg,
                androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm)
            )
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentItem(
            CaIcons.code,
            stringResource(Res.string.breadcrumb_code),
            mode == EditorViewMode.Text
        ) { onSelect(EditorViewMode.Text) }
        // The Blocks segment is present only when the `blocks` plugin is enabled (see IdeUiState.blocksEnabled).
        if (blocksEnabled) {
            SegmentItem(CaIcons.layers, stringResource(Res.string.breadcrumb_blocks), mode == EditorViewMode.Blocks) {
                onSelect(
                    EditorViewMode.Blocks
                )
            }
        }
        if (canPreview) {
            SegmentItem(CaIcons.image, stringResource(Res.string.breadcrumb_preview), mode == EditorViewMode.Preview) {
                onSelect(
                    EditorViewMode.Preview
                )
            }
            // Split = code + preview together, so you can edit and watch it update (the phone-friendly path).
            SegmentItem(CaIcons.split, stringResource(Res.string.breadcrumb_split), mode == EditorViewMode.Split) {
                onSelect(
                    EditorViewMode.Split
                )
            }
        }
    }
}

@Composable
private fun SegmentItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    // Icon-only; the label rides along as the icon's content description (accessibility) but is never drawn.
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.xs))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.xs)
            )
            .clickable(
                remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                null,
                onClick = onClick
            )
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon,
            label,
            Modifier.size(15.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
