package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.ui.ext.keymapKeyName
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.keymap_hint
import dev.ide.ui.generated.resources.keymap_modified
import dev.ide.ui.generated.resources.keymap_no_results
import dev.ide.ui.generated.resources.keymap_not_set
import dev.ide.ui.generated.resources.keymap_recording
import dev.ide.ui.generated.resources.keymap_reset
import dev.ide.ui.generated.resources.keymap_reset_all
import dev.ide.ui.generated.resources.keymap_search
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.shortcutKeys
import dev.ide.ui.platform.shortcutText
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/** The widest the keymap column grows; past it the page centres rather than pushing keys to the far edge. */
private val KEYMAP_MAX_WIDTH = 760.dp

/**
 * The Keymap page: every command and its shortcut, grouped, searchable by command name or by key, and filterable
 * to what the user has changed. A shortcut is drawn as keycaps in this platform's notation and recorded by
 * clicking it and pressing the keys. How to record is said once, above the list; a row carries a second line
 * only for a conflict.
 */
@Composable
internal fun KeymapPageContent(
    page: UiSettingsPage,
    values: MutableMap<String, String>,
    onSet: (String, String, String) -> Unit,
    onStructuralChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var modifiedOnly by remember { mutableStateOf(false) }
    val shortcuts = page.controls.filterIsInstance<UiSettingControl.Shortcut>()
    // A STORED blank is a shortcut the user cleared, so a stored value is used as-is rather than falling back to
    // the control's value when it is empty; the value already carries the default for an unset key.
    fun current(c: UiSettingControl.Shortcut): String = values["${page.id}.${c.key}"] ?: c.value
    val titles = shortcuts.associate { it.key to localizedControlTitle(page.id, it) }
    val modified = shortcuts.filter { current(it) != it.defaultValue }
    val q = query.trim()
    val shown = shortcuts.filter { c ->
        (!modifiedOnly || current(c) != c.defaultValue) &&
            (q.isEmpty() || titles.getValue(c.key).contains(q, ignoreCase = true) ||
                shortcutText(current(c)).contains(q, ignoreCase = true) ||
                shortcutText(current(c), commandKey = false).contains(q, ignoreCase = true))
    }
    val record = { c: UiSettingControl.Shortcut, spec: String -> onSet(page.id, c.key, spec); onStructuralChange() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val gutter = maxOf(16.dp, (maxWidth - KEYMAP_MAX_WIDTH) / 2)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("toolbar") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.keymap_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.keymap_search)) },
                        leadingIcon = { Icon(CaIcons.search, null, Modifier.size(18.dp)) },
                        trailingIcon = if (query.isEmpty()) null else ({
                            IconButton(onClick = { query = "" }) { Icon(CaIcons.close, null, Modifier.size(16.dp)) }
                        }),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = modifiedOnly,
                            onClick = { modifiedOnly = !modifiedOnly },
                            label = { Text("${stringResource(Res.string.keymap_modified)} · ${modified.size}") },
                            leadingIcon = if (modifiedOnly) ({ Icon(CaIcons.check, null, Modifier.size(16.dp)) }) else null,
                        )
                        Spacer(Modifier.weight(1f))
                        if (modified.isNotEmpty()) {
                            TextButton(onClick = {
                                modified.forEach { onSet(page.id, it.key, it.defaultValue) }
                                onStructuralChange()
                            }) { Text(stringResource(Res.string.keymap_reset_all)) }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item("empty") {
                    Text(
                        stringResource(Res.string.keymap_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            shown.groupBy { it.group }.forEach { (group, controls) ->
                item(group ?: "_") {
                    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        localizedGroup(group)?.let {
                            Text(
                                it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        controls.forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            val spec = current(c)
                            KeymapRow(
                                title = titles.getValue(c.key),
                                conflict = localizedControlDescription(page.id, c),
                                spec = spec,
                                isDefault = spec == c.defaultValue,
                                onRecord = { record(c, it) },
                                onReset = { record(c, c.defaultValue) },
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeymapRow(
    title: String,
    conflict: String?,
    spec: String,
    isDefault: Boolean,
    onRecord: (String) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isDefault) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (conflict != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(CaIcons.warning, null, Modifier.size(13.dp), tint = Ide.colors.warning)
                    Text(conflict, color = Ide.colors.warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!isDefault) {
            IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                Icon(CaIcons.undo, stringResource(Res.string.keymap_reset), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ShortcutRecorder(spec, onRecord)
    }
}

/**
 * A shortcut drawn as keycaps that records a new one when clicked: the next key pressed with a modifier becomes
 * the value. A bare key is ignored (it is a letter the user wants to type), Escape cancels, Backspace clears, and
 * clicking elsewhere stops recording.
 */
@Composable
private fun ShortcutRecorder(spec: String, onRecord: (String) -> Unit) {
    var recording by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(recording) { if (recording) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(10.dp)
    val description = if (spec.isEmpty()) stringResource(Res.string.keymap_not_set) else shortcutText(spec)
    Box(
        Modifier
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused) recording = false }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (!recording || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Escape -> recording = false
                    Key.Backspace, Key.Delete -> {
                        recording = false
                        onRecord("")
                    }
                    else -> {
                        val name = keymapKeyName(ev.key)
                        if (name != null && (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed)) {
                            recording = false
                            onRecord(recordedSpec(name, ev.isCtrlPressed || ev.isMetaPressed, ev.isAltPressed, ev.isShiftPressed))
                        }
                    }
                }
                true
            }
            .clip(shape)
            .then(
                if (recording) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .clickable { recording = !recording }
            .semantics { role = Role.Button; contentDescription = description }
            .widthIn(min = 72.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        when {
            recording -> Text(stringResource(Res.string.keymap_recording), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            spec.isEmpty() -> Text(stringResource(Res.string.keymap_not_set), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                shortcutKeys(spec).forEachIndexed { i, stroke ->
                    if (i > 0) Spacer(Modifier.width(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { stroke.forEach { Keycap(it) } }
                }
            }
        }
    }
}

@Composable
private fun Keycap(label: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** The canonical spec for a recorded press; `primary` so the binding means Command on a Mac and Control elsewhere. */
private fun recordedSpec(key: String, primary: Boolean, alt: Boolean, shift: Boolean): String = buildString {
    if (primary) append("primary+")
    if (alt) append("alt+")
    if (shift) append("shift+")
    append(key)
}
