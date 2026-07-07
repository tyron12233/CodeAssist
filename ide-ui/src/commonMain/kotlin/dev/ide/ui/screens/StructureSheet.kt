package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.UiFileSymbol
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.structure_filter
import dev.ide.ui.generated.resources.structure_no_matches
import dev.ide.ui.generated.resources.structure_no_symbols
import dev.ide.ui.generated.resources.structure_title
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.stringResource

/**
 * The in-file structure / outline as a bottom sheet: the file's declarations (classes, methods, fields…),
 * nested by depth, with a filter field. Tapping an item navigates the caret to it and dismisses. The list is
 * fetched from [dev.ide.ui.backend.EditorService.fileStructure] whenever the sheet is open, refreshed
 * (debounced) as the buffer changes so it stays current.
 */
@Composable
internal fun StructureSheet(state: IdeUiState, active: OpenFile) {
    var symbols by remember(active.path) { mutableStateOf<List<UiFileSymbol>>(emptyList()) }
    var filter by remember(active.path) { mutableStateOf("") }
    // (Re)load while the sheet is open; key on the buffer revision so edits made before reopening are reflected.
    LaunchedEffect(state.structureOpen, active.path, active.session.textRevision) {
        if (!state.structureOpen) return@LaunchedEffect
        delay(120.milliseconds)
        symbols = runCatching { state.backend.editor.fileStructure(active.path, active.text) }.getOrDefault(emptyList())
    }
    val shown = remember(symbols, filter) {
        if (filter.isBlank()) symbols
        else symbols.filter { it.name.contains(filter, ignoreCase = true) }
    }
    BottomSheet(visible = state.structureOpen, onDismiss = { state.structureOpen = false }, heightFraction = 0.6f) {
        Text(
            stringResource(Res.string.structure_title),
            color = Ca.colors.textSecondary,
            style = Ca.type.caption,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        // Filter field — a structure sheet that also narrows by name (handy in a big file).
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (filter.isEmpty()) Text(stringResource(Res.string.structure_filter), color = Ca.colors.textTertiary, style = Ca.type.body)
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(6.dp))
        if (shown.isEmpty()) {
            Text(
                if (symbols.isEmpty()) stringResource(Res.string.structure_no_symbols) else stringResource(Res.string.structure_no_matches),
                color = Ca.colors.textTertiary,
                style = Ca.type.body,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown) { sym ->
                    StructureRow(sym) {
                        state.openAt(active.path, sym.nameOffset)
                        state.structureOpen = false
                    }
                }
            }
        }
    }
}

@Composable
private fun StructureRow(sym: UiFileSymbol, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            // Indent nested members; the filter never changes an item's own depth.
            .padding(start = (12 + sym.depth * 16).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KindBadge(sym.kind)
        Text(sym.name, color = Ca.colors.textPrimary, style = Ca.type.body, fontWeight = FontWeight.Medium)
        if (!sym.detail.isNullOrEmpty()) {
            Text(sym.detail, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1)
        }
    }
}

/** A small colored letter badge for a symbol kind (IntelliJ-style c/i/m/f gutter marks). */
@Composable
private fun KindBadge(kind: String) {
    val (letter, color) = when (kind) {
        "class", "record" -> "C" to Ca.colors.warning
        "interface" -> "I" to Ca.colors.info
        "enum" -> "E" to Ca.colors.warning
        "annotation_type" -> "@" to Ca.colors.info
        "method" -> "m" to Ca.colors.accent
        "constructor" -> "c" to Ca.colors.accent
        "field" -> "f" to Ca.colors.info
        "enum_constant" -> "e" to Ca.colors.success
        else -> "•" to Ca.colors.textTertiary
    }
    Box(
        Modifier.width(18.dp).size(18.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.xs)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = color, style = Ca.type.codeSmall, fontWeight = FontWeight.Bold)
    }
}
