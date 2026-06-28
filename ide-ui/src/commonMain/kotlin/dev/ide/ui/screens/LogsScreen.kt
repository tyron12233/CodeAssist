package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLogEntry
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which severities the Logs viewer shows. */
private enum class LogFilter(val label: String, val keep: (String) -> Boolean) {
    All("All", { true }),
    Warnings("Warnings", { it == "WARN" || it == "ERROR" }),
    Errors("Errors", { it == "ERROR" }),
}

/**
 * The in-app Logs viewer: a live tail of the logging facade's ring buffer — editor, analysis, indexing,
 * build, and crash activity — so a user can see *what actually went wrong* when a feature "does nothing".
 * Newest first; filter by severity or text; tap a record with an exception to expand its stack trace; copy
 * the whole view or share it as a file ([FileActions.share]). Opened from the More menu.
 */
@Composable
fun LogsScreen(
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
) {
    var all by remember { mutableStateOf(backend.diagnostics.recentLogs()) }
    var filter by remember { mutableStateOf(LogFilter.All) }
    var query by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Live tail: refresh from the ring buffer periodically while the sheet is open (cheap — a snapshot of at
    // most a few hundred records), unless the user paused it (so they can read without the list shifting).
    LaunchedEffect(paused) {
        while (!paused) {
            all = backend.diagnostics.recentLogs()
            delay(1500)
        }
    }

    val q = query.trim()
    val shown = remember(all, filter, q) {
        all.asReversed().filter { e ->
            filter.keep(e.level) &&
                (q.isEmpty() || e.message.contains(q, true) || e.tag.contains(q, true) || (e.stackTrace?.contains(q, true) == true))
        }
    }

    Column(modifier.fillMaxSize().background(Ca.colors.bg)) {
        // Header: title + actions
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(CaIcons.terminal, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
            Text("Logs", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            Text("${shown.size}", color = Ca.colors.textTertiary, style = Ca.type.footnote, modifier = Modifier.padding(start = 4.dp))
            Box(Modifier.weight(1f))
            HeaderAction(if (paused) CaIcons.play else CaIcons.stop, if (paused) "Resume live tail" else "Pause live tail", paused) { paused = !paused }
            HeaderAction(CaIcons.refresh, "Refresh") { all = backend.diagnostics.recentLogs() }
            HeaderAction(CaIcons.copy, "Copy all") {
                clipboard.setText(AnnotatedString(shown.joinToString("\n\n") { renderForCopy(it) }))
            }
            if (fileActions.canShare) {
                HeaderAction(CaIcons.share, "Share") {
                    scope.launch { backend.diagnostics.exportLogs()?.let { fileActions.share(it) } }
                }
            }
        }

        // Search field
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.search, null, Modifier.size(16.dp), tint = Ca.colors.accent)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Filter logs…", color = Ca.colors.textTertiary, style = Ca.type.subhead)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary),
                    cursorBrush = SolidColor(Ca.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Severity filter chips
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogFilter.entries.forEach { f -> FilterChip(f.label, f == filter) { filter = f } }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        // Records
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (all.isEmpty()) "No logs yet." else "No logs match this filter.",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.footnote,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                itemsIndexed(
                    shown,
                    key = { index, it -> "$index:${it.timestampMs}:${it.tag}:${it.message.hashCode()}" },
                ) { _, it -> LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: UiLogEntry) {
    var expanded by remember(entry) { mutableStateOf(false) }
    val color = levelColor(entry.level)
    val hasTrace = entry.stackTrace != null
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .let { if (hasTrace) it.clickable { expanded = !expanded } else it }
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.timeLabel, color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
            LevelBadge(entry.level, color)
            Column(Modifier.weight(1f)) {
                Text(entry.message, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
                Text(
                    entry.tag,
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasTrace) {
                Icon(
                    if (expanded) CaIcons.chevronDown else CaIcons.chevronRight,
                    null,
                    Modifier.size(14.dp).padding(top = 2.dp),
                    tint = Ca.colors.textTertiary,
                )
            }
        }
        if (expanded && entry.stackTrace != null) {
            Text(
                entry.stackTrace,
                color = Ca.colors.textSecondary,
                style = Ca.type.codeSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)
                    .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(8.dp),
            )
        }
    }
}

@Composable
private fun LevelBadge(level: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(level, color = color, style = Ca.type.caption2, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(32.dp)
            .background(if (active) Ca.colors.accentSoft else Color.Transparent, RoundedCornerShape(Ca.radius.sm))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(17.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Ca.colors.accent else Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .border(1.dp, if (selected) Color.Transparent else Ca.colors.hairline, RoundedCornerShape(Ca.radius.pill))
            .clickable(MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (selected) Ca.colors.textOnAccent else Ca.colors.textSecondary,
            style = Ca.type.caption,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "ERROR" -> Ca.colors.error
    "WARN" -> Ca.colors.warning
    "INFO" -> Ca.colors.info
    else -> Ca.colors.textTertiary
}

private fun renderForCopy(e: UiLogEntry): String = buildString {
    append("${e.timeLabel} [${e.level}] ${e.tag} (${e.thread}): ${e.message}")
    e.stackTrace?.let { append('\n').append(it) }
}
