package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

class PaletteEntry(val section: String, val label: String, val sub: String?, val run: () -> Unit)

/**
 * The command palette: drops from the top (glass-thick). One input over commands, files, and — backed
 * by the index — **Go-to-Symbol** (navigable project declarations) and **Member search** across the
 * classpath. Enter runs the top result; Esc closes.
 */
@Composable
fun CommandPalette(
    files: List<TreeNode>,
    backend: IdeBackend,
    onOpenFile: (TreeNode) -> Unit,
    onOpenAt: (String, Int) -> Unit,
    onToggleTheme: () -> Unit,
    onReindex: () -> Unit,
    onOpenDependencies: () -> Unit,
    onManageSdk: () -> Unit = {},
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    var members by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 2) {
            symbols = runCatching { backend.searchSymbols(q, 20) }.getOrDefault(emptyList())
            members = runCatching { backend.searchMembers(q, 20) }.getOrDefault(emptyList())
        } else { symbols = emptyList(); members = emptyList() }
    }

    val q = query.trim()
    val entries = buildList {
        if (q.isEmpty()) {
            add(PaletteEntry("Run", "Run build", "⌘R") {})
            add(PaletteEntry("Commands", "Manage dependencies", null, onOpenDependencies))
            add(PaletteEntry("Commands", "Manage SDK (Android & JDK)", null, onManageSdk))
            add(PaletteEntry("Commands", "Toggle theme (light/dark)", null, onToggleTheme))
            add(PaletteEntry("Commands", "Re-index project", null, onReindex))
            files.take(12).forEach { f -> add(PaletteEntry("Go to", f.name, null) { onOpenFile(f) }) }
        } else {
            symbols.forEach { s ->
                add(PaletteEntry("Symbols", s.name, s.detail) {
                    if (s.filePath != null && s.offset != null) onOpenAt(s.filePath, s.offset)
                })
            }
            files.filter { it.name.contains(q, ignoreCase = true) }.take(8)
                .forEach { f -> add(PaletteEntry("Files", f.name, null) { onOpenFile(f) }) }
            members.forEach { m -> add(PaletteEntry("Members", m.name, m.detail) {}) }
            if ("toggle theme".contains(q, ignoreCase = true)) {
                add(PaletteEntry("Commands", "Toggle theme (light/dark)", null, onToggleTheme))
            }
            if ("manage dependencies".contains(q, ignoreCase = true)) {
                add(PaletteEntry("Commands", "Manage dependencies", null, onOpenDependencies))
            }
            if ("manage sdk".contains(q, ignoreCase = true) || "android sdk".contains(q, ignoreCase = true) || "jdk".contains(q, ignoreCase = true)) {
                add(PaletteEntry("Commands", "Manage SDK (Android & JDK)", null, onManageSdk))
            }
            if ("re-index project".contains(q, ignoreCase = true)) {
                add(PaletteEntry("Commands", "Re-index project", null, onReindex))
            }
        }
    }

    // The scrim + drop-from-top entrance are provided by the hosting DropdownOverlay; this is just the
    // glass body. Adaptive width: full-bleed (minus 12dp margins) on phone, capped at 600 on desktop.
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.command, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Run a command, jump to a file or symbol…", color = Ca.colors.textTertiary, style = Ca.type.body)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary),
                    cursorBrush = SolidColor(Ca.colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Escape -> { onClose(); true }
                            Key.Enter -> { entries.firstOrNull()?.let { it.run(); onClose() }; true }
                            else -> false
                        }
                    },
                )
            }
            Chip("esc", fill = Ca.colors.surface3, textColor = Ca.colors.textSecondary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(entries) { entry -> PaletteRow(entry, onClose) }
        }
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { entry.run(); onClose() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
            Text(entry.section.uppercase(), color = Ca.colors.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            entry.label, color = Ca.colors.textPrimary, style = Ca.type.subhead,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (entry.sub != null) {
            Spacer(Modifier.width(8.dp))
            Text(entry.sub, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
