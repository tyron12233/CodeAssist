package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.editor_notices_many
import dev.ide.ui.generated.resources.hide_details
import dev.ide.ui.generated.resources.show_details
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/** How loud a notice is. [Warning] means the project will not fully work; [Info] is a capability notice. */
internal enum class NoticeLevel { Warning, Info }

/**
 * One thing the editor has to tell you about the project or the open file.
 *
 * [summary] is the whole notice when it is the only one; it is also the row shown for it in the expanded
 * list. [actionLabel] with [onAction] is the one thing to do about it, and a notice whose real explanation
 * needs a card of its own (the Gradle-compatibility strip, the toolchain warnings) points its action at
 * revealing that card rather than trying to be it.
 */
@Immutable
internal class EditorNotice(
    val id: String,
    val level: NoticeLevel,
    val summary: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Null for a notice that must not be hidden (it describes the file you are looking at right now). */
    val onDismiss: (() -> Unit)? = null,
)

/**
 * Every project-level notice as ONE bar.
 *
 * The editor used to stack these: the top bar, then a dependency progress line, then a Gradle-compatibility
 * strip, then an unrecognized-project strip, then the toolchain warnings, then the tab strip, then the
 * breadcrumb, then an Android-sources offer, then a read-only strip, then a large-file strip. Ten bars are
 * reachable, six or seven realistically, and on a phone that is half the screen before the first line of
 * code. A count is the part that matters at a glance; the detail is one tap away.
 *
 * The pattern is not new here: [ToolchainWarningBanner] already collapses its own several warnings behind one
 * summary row for exactly this reason. This is that idea one level up, across every notice rather than within
 * one of them.
 *
 * A single notice renders in place rather than as "1 thing needs attention", which would make the reader
 * open something to learn one sentence.
 */
@Composable
internal fun EditorNoticeStrip(notices: List<EditorNotice>, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val worst = if (notices.any { it.level == NoticeLevel.Warning }) NoticeLevel.Warning else NoticeLevel.Info
    val tint = if (worst == NoticeLevel.Warning) Ide.colors.warning else MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth().background(tint.copy(alpha = 0.10f))) {
        if (notices.size == 1) {
            NoticeRow(notices.single(), tint)
        } else {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = tint)
                Text(
                    stringResource(Res.string.editor_notices_many, notices.size),
                    color = tint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) CaIcons.caretDown else CaIcons.caretRight,
                    if (open) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                    Modifier.size(14.dp),
                    tint = tint,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.fillMaxWidth()) {
                    notices.forEach { NoticeRow(it, tint) }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(notice: EditorNotice, tint: androidx.compose.ui.graphics.Color) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            notice.summary,
            color = c.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val label = notice.actionLabel
        val act = notice.onAction
        if (label != null && act != null) {
            Text(
                label,
                color = tint,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = act).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        notice.onDismiss?.let { dismiss ->
            IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), dismiss, boxSize = 28, iconSize = 14)
        }
    }
}
