package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiVersionNote
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_whats_new_in
import dev.ide.ui.generated.resources.store_whats_new_less
import dev.ide.ui.generated.resources.store_whats_new_more
import dev.ide.ui.generated.resources.store_whats_new_no_notes
import dev.ide.ui.generated.resources.store_whats_new_since
import dev.ide.ui.markdown.linkify
import org.jetbrains.compose.resources.stringResource

/**
 * The release notes the project page leads with.
 *
 * With an older copy installed ([installedVersion]), every version since that one, newest first: someone on
 * 1.0.0 being offered 1.3.0 wants to know what 1.1.0 and 1.2.0 changed too, not only the last step. Otherwise
 * the live version's notes. [history] is the store's list, newest first; when it could not be fetched the
 * live version's own notes (carried on [item]) stand in, so the card never depends on the extra request.
 *
 * Empty when there is nothing to say: no notes at all is a missing card, not one that says "no notes".
 */
internal fun whatsNew(item: UiStoreItem, history: List<UiVersionNote>, installedVersion: String?): WhatsNew? {
    val live = item.version ?: return null
    val since = installedVersion?.takeIf { it != live && history.any { v -> v.version == it } }
    val notes = if (since != null) {
        history.takeWhile { it.version != since }
    } else {
        listOf(
            history.firstOrNull { it.version == live }
                ?: UiVersionNote(live, 0, item.changelog?.takeIf { it.isNotBlank() }),
        )
    }.take(MAX_VERSIONS)
    if (notes.all { it.changelog.isNullOrBlank() }) return null
    return WhatsNew(notes, since)
}

/** What the card shows: [notes] newest first, and the installed version they lead up from, if any. */
internal data class WhatsNew(val notes: List<UiVersionNote>, val sinceVersion: String?)

/** At most this many versions on the card; the Changelog tab has the rest. */
private const val MAX_VERSIONS = 10

/**
 * A changelog as one line: its first few non-blank lines, list markers dropped, joined with " · ". For a row
 * that has room for a hint of what changed and no more. Null when there is nothing to show.
 */
internal fun changelogPreview(changelog: String?): String? {
    val lines = changelog.orEmpty().lines()
        .map { it.trim().replace(LIST_MARKER, "").trim() }
        .filter { it.isNotEmpty() }
    return lines.take(3).joinToString("  ·  ").takeIf { it.isNotEmpty() }
}

private val LIST_MARKER = Regex("""^(?:[-*•]|#+|\d+[.)])\s*""")

/**
 * The card above the screenshots: "What's new in 1.2.0", or "What's new since 1.0.0" with each version in
 * between. Folded to the newest version's first lines; "Show more" opens every version in full.
 */
@Composable
internal fun WhatsNewCard(whatsNew: WhatsNew, now: Long) {
    val notes = whatsNew.notes
    val c = MaterialTheme.colorScheme
    var expanded by remember(notes) { mutableStateOf(false) }
    val since = whatsNew.sinceVersion
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow(
                if (since != null) stringResource(Res.string.store_whats_new_since, since)
                else stringResource(Res.string.store_whats_new_in, notes.first().version),
                color = c.primary,
            )
            // Folded: since an installed version, the newest three at two lines each, so the size of the gap shows
            // at a glance; otherwise the live version's first four lines.
            val foldCount = if (since != null) 3 else 1
            val foldLines = if (since != null) 2 else 4
            val shown = if (expanded) notes else notes.take(foldCount)
            shown.forEach { note ->
                VersionNoteBlock(note, now, showVersion = since != null || notes.size > 1 && expanded, maxLines = if (expanded) Int.MAX_VALUE else foldLines)
            }
            // Offered when there is more than the fold shows: other versions, or a long note.
            val more = notes.size > foldCount || shown.any { n ->
                val text = n.changelog.orEmpty()
                text.lines().size > foldLines || text.length > foldLines * 60
            }
            if (more) {
                TextButton({ expanded = !expanded }, Modifier.align(Alignment.End)) {
                    Text(stringResource(if (expanded) Res.string.store_whats_new_less else Res.string.store_whats_new_more))
                }
            }
        }
    }
}

/** Every published version's notes, newest first: the Changelog tab once the history has loaded. */
@Composable
internal fun VersionHistoryList(history: List<UiVersionNote>, now: Long) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        history.forEach { VersionNoteBlock(it, now, showVersion = true, maxLines = Int.MAX_VALUE) }
    }
}

@Composable
private fun VersionNoteBlock(note: UiVersionNote, now: Long, showVersion: Boolean, maxLines: Int) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showVersion) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("v${note.version}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = c.onSurface)
                isoMillis(note.publishedAt)?.let { at -> releaseAge(at, now) }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
        }
        val text = note.changelog
        if (text.isNullOrBlank()) {
            Text(stringResource(Res.string.store_whats_new_no_notes), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        } else {
            SelectionContainer {
                Text(
                    linkify(text, SpanStyle(), SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
