package dev.ide.ui.editor

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.find_all
import dev.ide.ui.generated.resources.find_case_sensitive
import dev.ide.ui.generated.resources.find_next_match
import dev.ide.ui.generated.resources.find_placeholder
import dev.ide.ui.generated.resources.find_previous_match
import dev.ide.ui.generated.resources.find_regex
import dev.ide.ui.generated.resources.find_replace
import dev.ide.ui.generated.resources.find_replace_placeholder
import dev.ide.ui.generated.resources.find_toggle_replace
import dev.ide.ui.generated.resources.find_whole_word
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The in-file find/replace bar, docked at the top of the editor. Find row: query field + match count +
 * prev/next + the Aa (case) / W (whole-word) / .* (regex) toggles + close, and a chevron that reveals the
 * replace row (replace field + Replace / Replace all). Enter = next match, Shift-Enter = prev, Esc = close.
 * Pure UI over hoisted state; all match computation + editing lives in the editor.
 */
@Composable
fun FindReplaceBar(
    query: String,
    replace: String,
    replaceMode: Boolean,
    options: FindOptions,
    matchCount: Int,
    currentIndex: Int,
    regexError: Boolean,
    onQueryChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onToggleReplaceMode: () -> Unit,
    onOptionsChange: (FindOptions) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(bottomStart = Ca.radius.md, bottomEnd = Ca.radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ide.colors.glassThick, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButtonCa(
                if (replaceMode) CaIcons.chevronDown else CaIcons.chevronRight,
                stringResource(Res.string.find_toggle_replace), onClick = onToggleReplaceMode, iconSize = 16, boxSize = 30,
            )
            Box(Modifier.weight(1f)) {
                FieldBox(
                    value = query,
                    onChange = onQueryChange,
                    placeholder = stringResource(Res.string.find_placeholder),
                    focusRequester = focus,
                    error = regexError,
                    onEnter = { shift -> if (shift) onPrev() else onNext() },
                    onEscape = onClose,
                )
            }
            val countText = when {
                query.isEmpty() -> ""
                matchCount == 0 -> "0/0"
                else -> "${currentIndex + 1}/$matchCount"
            }
            Text(
                countText,
                color = if (matchCount == 0 && query.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.widthIn(min = 34.dp),
            )
            OptionChip(stringResource(Res.string.find_case_sensitive), options.caseSensitive) { onOptionsChange(options.copy(caseSensitive = !options.caseSensitive)) }
            OptionChip(stringResource(Res.string.find_whole_word), options.wholeWord) { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) }
            OptionChip(stringResource(Res.string.find_regex), options.regex) { onOptionsChange(options.copy(regex = !options.regex)) }
            IconButtonCa(CaIcons.chevronUp, stringResource(Res.string.find_previous_match), onClick = onPrev, iconSize = 16, boxSize = 30)
            IconButtonCa(CaIcons.chevronDown, stringResource(Res.string.find_next_match), onClick = onNext, iconSize = 16, boxSize = 30)
            IconButtonCa(CaIcons.close, stringResource(Res.string.close), onClick = onClose, iconSize = 16, boxSize = 30)
        }
        if (replaceMode) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(30.dp)) {} // align under the chevron
                Box(Modifier.weight(1f)) {
                    FieldBox(value = replace, onChange = onReplaceChange, placeholder = stringResource(Res.string.find_replace_placeholder), error = false)
                }
                PillButton(stringResource(Res.string.find_replace), enabled = matchCount > 0, onClick = onReplaceOne)
                PillButton(stringResource(Res.string.find_all), enabled = matchCount > 0, onClick = onReplaceAll)
            }
        }
    }
}

@Composable
private fun FieldBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    error: Boolean,
    focusRequester: FocusRequester? = null,
    onEnter: ((shift: Boolean) -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
) {
    // Keep a TextFieldValue locally; on an external change (e.g. a query seeded from the selection) reset the
    // caret to the end so typing appends rather than inserting at column 0.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.outline, style = Ide.type.code)
        BasicTextField(
            value = field,
            onValueChange = { field = it; onChange(it.text) },
            singleLine = true,
            textStyle = Ide.type.code.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> { onEnter?.invoke(ev.isShiftPressed); onEnter != null }
                        Key.Escape -> { onEscape?.invoke(); onEscape != null }
                        else -> false
                    }
                },
        )
    }
}

@Composable
private fun OptionChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(Ca.radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PillButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(Ca.radius.sm))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
