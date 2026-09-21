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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ColorPickerDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.SettingsActionRow
import dev.ide.ui.components.SettingsCard
import dev.ide.ui.components.SettingsChoiceRow
import dev.ide.ui.components.SettingsDivider
import dev.ide.ui.components.SettingsToggleRow
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.colors_background
import dev.ide.ui.generated.resources.colors_background_none
import dev.ide.ui.generated.resources.colors_bold
import dev.ide.ui.generated.resources.colors_builtin_notice
import dev.ide.ui.generated.resources.colors_clear
import dev.ide.ui.generated.resources.colors_copied
import dev.ide.ui.generated.resources.colors_copy
import dev.ide.ui.generated.resources.colors_customized
import dev.ide.ui.generated.resources.colors_default
import dev.ide.ui.generated.resources.colors_delete
import dev.ide.ui.generated.resources.colors_delete_confirm
import dev.ide.ui.generated.resources.colors_duplicate
import dev.ide.ui.generated.resources.colors_editing_copy
import dev.ide.ui.generated.resources.colors_export
import dev.ide.ui.generated.resources.colors_foreground
import dev.ide.ui.generated.resources.colors_import
import dev.ide.ui.generated.resources.colors_import_failed
import dev.ide.ui.generated.resources.colors_imported
import dev.ide.ui.generated.resources.colors_inherit
import dev.ide.ui.generated.resources.colors_inherit_desc
import dev.ide.ui.generated.resources.colors_inherited_from
import dev.ide.ui.generated.resources.colors_italic
import dev.ide.ui.generated.resources.colors_overlay_note
import dev.ide.ui.generated.resources.colors_paste
import dev.ide.ui.generated.resources.colors_paste_hint
import dev.ide.ui.generated.resources.colors_preview
import dev.ide.ui.generated.resources.colors_preview_hint
import dev.ide.ui.generated.resources.colors_rename
import dev.ide.ui.generated.resources.colors_reset
import dev.ide.ui.generated.resources.colors_reset_entry
import dev.ide.ui.generated.resources.colors_scheme
import dev.ide.ui.generated.resources.colors_scheme_desc
import dev.ide.ui.generated.resources.colors_scheme_name
import dev.ide.ui.generated.resources.colors_strikethrough
import dev.ide.ui.generated.resources.colors_title
import dev.ide.ui.generated.resources.colors_underline
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.save
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.colors.AttributeStyle
import dev.ide.ui.theme.colors.ColorAttribute
import dev.ide.ui.theme.colors.ColorAttributes
import dev.ide.ui.theme.colors.ColorSchemeStore
import dev.ide.ui.theme.colors.EditorColorScheme
import dev.ide.ui.theme.colors.ResolvedColorScheme
import dev.ide.ui.theme.colors.toArgbLong
import dev.ide.ui.theme.colors.toHex
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A transient acknowledgement shown under the scheme picker. Modelled rather than stored as a finished
 * string so the text stays a string resource: the message is built in composition, where `stringResource`
 * can be called, not at the point the action fires.
 */
private sealed interface SchemeNotice {
    data object Copied : SchemeNotice
    data object ImportFailed : SchemeNotice
    data class Imported(val name: String) : SchemeNotice
}

/**
 * The editor color scheme editor: pick or build a scheme, tune any entry, and watch a staged editor repaint
 * as you go.
 *
 * The attribute list is generated from [ColorAttributes], not written out here, which is the point of the
 * registry — a language that registers its own constructs gets them listed, grouped and editable on this
 * screen without the screen knowing it exists.
 *
 * [onImportFile]/[onExportFile] are optional because not every host can reach the filesystem the same way;
 * where they are absent the clipboard still carries a scheme in and out, so import/export is never missing
 * entirely — it just loses the file picker.
 */
@Composable
fun ColorSchemeScreen(
    store: ColorSchemeStore,
    isDark: Boolean,
    onApply: (EditorColorScheme) -> Unit,
    onImportFile: ((onJson: (String) -> Unit) -> Unit)? = null,
    onExportFile: ((fileName: String, json: String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val state = rememberColorSchemeScreenState(store, isDark, onApply)
    // The live theme's resolution of the scheme being edited: it already carries the chrome fallbacks the
    // Material theme supplies, so the preview shows what the editor will actually render, not an
    // approximation built from the override map.
    val colors = Ide.editorColors
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    var renaming by remember { mutableStateOf(false) }
    var duplicating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var notice: SchemeNotice? by remember { mutableStateOf(null) }

    // A notice clears itself, so the screen does not accumulate stale banners as the user works.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    ExpressiveScaffold(title = stringResource(Res.string.colors_title), onBack = onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsCard(null) {
                SettingsChoiceRow(
                    stringResource(Res.string.colors_scheme),
                    stringResource(Res.string.colors_scheme_desc),
                    state.scheme.id,
                    state.schemes.map { it.id to it.name },
                ) { state.select(it) }

                val forked = state.forkedInto
                when {
                    forked != null -> Notice(stringResource(Res.string.colors_editing_copy, forked))
                    state.scheme.builtIn -> Notice(stringResource(Res.string.colors_builtin_notice, state.scheme.name))
                }
                notice?.let { Notice(noticeText(it)) }

                SettingsDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(stringResource(Res.string.colors_duplicate), Modifier.weight(1f)) { duplicating = true }
                    if (!state.scheme.builtIn) {
                        ActionChip(stringResource(Res.string.colors_rename), Modifier.weight(1f)) { renaming = true }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(stringResource(Res.string.colors_copy), Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(state.exportJson()))
                        notice = SchemeNotice.Copied
                    }
                    ActionChip(stringResource(Res.string.colors_paste), Modifier.weight(1f)) { pasting = true }
                }
                if (onImportFile != null || onExportFile != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onImportFile?.let { pick ->
                            ActionChip(stringResource(Res.string.colors_import), Modifier.weight(1f)) {
                                pick { json ->
                                    notice = state.import(json)
                                        ?.let { SchemeNotice.Imported(it) } ?: SchemeNotice.ImportFailed
                                }
                            }
                        }
                        onExportFile?.let { export ->
                            ActionChip(stringResource(Res.string.colors_export), Modifier.weight(1f)) {
                                export("${state.scheme.id}.colors.json", state.exportJson())
                            }
                        }
                    }
                }
                if (!state.scheme.builtIn) {
                    SettingsDivider()
                    SettingsActionRow(
                        stringResource(Res.string.colors_reset), null,
                        stringResource(Res.string.colors_reset), destructive = false,
                    ) { state.resetVariant() }
                    SettingsActionRow(
                        stringResource(Res.string.colors_delete), null,
                        stringResource(Res.string.delete), destructive = true,
                    ) { confirmDelete = true }
                }
            }

            SettingsCard(stringResource(Res.string.colors_preview)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorSchemeSamples.all.forEach { candidate ->
                        SampleTab(candidate.title, candidate.id == state.sample.id) { state.showSample(candidate) }
                    }
                }
                ColorSchemePreview(colors, state.sample, onPickAttribute = state::openEditor)
                Text(
                    stringResource(Res.string.colors_preview_hint),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            state.groups.forEach { (group, attributes) ->
                SettingsCard(group.title) {
                    attributes.forEachIndexed { index, attribute ->
                        if (index > 0) SettingsDivider()
                        AttributeRow(attribute, colors, state.overrideFor(attribute.key)) {
                            state.openEditor(attribute.key)
                        }
                    }
                }
            }
        }
    }

    // The last attribute is kept while the sheet animates out, so dismissing it slides away instead of
    // vanishing — the same keep-alive the completion popup uses.
    val editing = state.editing
    var lastEditing: ColorAttribute? by remember { mutableStateOf(null) }
    if (editing != null) lastEditing = editing
    lastEditing?.let { attribute ->
        AttributeEditorSheet(
            visible = editing != null,
            attribute = attribute,
            resolved = colors.styleOf(attribute.key),
            override = state.overrideFor(attribute.key),
            onChange = { state.setOverride(attribute.key, it) },
            onReset = { state.resetAttribute(attribute.key) },
            onDismiss = state::closeEditor,
        )
    }

    TextPromptDialog(
        visible = renaming,
        title = stringResource(Res.string.colors_rename),
        initial = state.scheme.name,
        placeholder = stringResource(Res.string.colors_scheme_name),
        onDismiss = { renaming = false },
    ) {
        state.rename(it)
        renaming = false
    }

    TextPromptDialog(
        visible = duplicating,
        title = stringResource(Res.string.colors_duplicate),
        initial = "${state.scheme.name} copy",
        placeholder = stringResource(Res.string.colors_scheme_name),
        onDismiss = { duplicating = false },
    ) {
        state.duplicate(it)
        duplicating = false
    }

    TextPromptDialog(
        visible = pasting,
        title = stringResource(Res.string.colors_paste),
        initial = "",
        placeholder = stringResource(Res.string.colors_paste_hint),
        multiline = true,
        onDismiss = { pasting = false },
    ) { json ->
        pasting = false
        // An empty box means "use whatever is on the clipboard" — pasting into a field on a phone is the
        // fiddly step, and the scheme is usually already there from another device's Copy.
        val text = json.ifBlank { clipboard.getText()?.text.orEmpty() }
        notice = state.import(text)?.let { SchemeNotice.Imported(it) } ?: SchemeNotice.ImportFailed
    }

    ConfirmDialog(
        visible = confirmDelete,
        message = stringResource(Res.string.colors_delete_confirm, state.scheme.name),
        confirmLabel = stringResource(Res.string.delete),
        onDismiss = { confirmDelete = false },
    ) {
        state.delete()
        confirmDelete = false
    }
}

// ---- rows ----------------------------------------------------------------------------------------------

/**
 * One attribute: its name, where its current look comes from, and a swatch of that look.
 *
 * The subtitle is the part that makes a fallback chain usable — "Inherited from Function call" tells the
 * user why an entry they never touched is blue, and which entry to change if they want every function blue
 * rather than just this one.
 */
@Composable
private fun AttributeRow(
    attribute: ColorAttribute,
    colors: ResolvedColorScheme,
    override: AttributeStyle,
    onClick: () -> Unit,
) {
    val style = colors.styleOf(attribute.key)
    val parentTitle = attribute.parent?.let { ColorAttributes.byKey(it)?.title }
    val subtitle = when {
        !override.isEmpty -> stringResource(Res.string.colors_customized)
        // An attribute with no default of its own is showing its parent's color, and saying so is what
        // makes the fallback chain usable: it names the entry to change to move a whole family at once.
        parentTitle != null && attribute.defaultFor(colors.isDark).isEmpty ->
            stringResource(Res.string.colors_inherited_from, parentTitle)
        else -> stringResource(Res.string.colors_default)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                attribute.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(subtitle, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        }
        // The swatch renders the attribute as the editor would: a fill for a background attribute, the
        // letters "Ab" in the real ink and the real font style for everything else.
        Swatch(style, attribute)
    }
}

@Composable
private fun Swatch(style: AttributeStyle, attribute: ColorAttribute) {
    val shape = RoundedCornerShape(Ca.radius.sm)
    val fill = style.background ?: Color.Transparent
    Box(
        Modifier
            .size(width = 46.dp, height = 30.dp)
            .background(if (attribute.backgroundOnly) fill else Ide.colors.editorBg, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!attribute.backgroundOnly) {
            Box(Modifier.fillMaxSize().background(fill, shape))
            Text(
                "Ab",
                color = style.foreground ?: MaterialTheme.colorScheme.onSurface,
                style = Ide.type.codeSmall,
                fontWeight = if (style.bold == true) FontWeight.Medium else null,
                fontStyle = if (style.italic == true) FontStyle.Italic else null,
                textDecoration = decorationOf(style),
            )
        }
    }
}

private fun decorationOf(style: AttributeStyle): TextDecoration? = when {
    style.underline == true && style.strikethrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    style.underline == true -> TextDecoration.Underline
    style.strikethrough == true -> TextDecoration.LineThrough
    else -> null
}

// ---- the attribute editor ------------------------------------------------------------------------------

/**
 * The sheet that edits one attribute.
 *
 * The switches show the RESOLVED value (what the editor is drawing now) and writing one records it
 * explicitly, so turning italic off on an entry that inherits italic actually turns it off rather than
 * silently falling back on. "Reset this entry" is the way back to inheriting.
 */
@Composable
private fun AttributeEditorSheet(
    visible: Boolean,
    attribute: ColorAttribute,
    resolved: AttributeStyle,
    override: AttributeStyle,
    onChange: (AttributeStyle) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingForeground by remember(attribute.key) { mutableStateOf(false) }
    var pickingBackground by remember(attribute.key) { mutableStateOf(false) }
    val parentTitle = attribute.parent?.let { ColorAttributes.byKey(it)?.title }

    BottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.62f) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                attribute.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (attribute.overlay) {
                Text(
                    stringResource(Res.string.colors_overlay_note),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (!attribute.backgroundOnly) {
                ColorRow(
                    stringResource(Res.string.colors_foreground),
                    resolved.foreground,
                    onClear = if (override.foreground != null) ({ onChange(override.copy(foreground = null)) }) else null,
                ) { pickingForeground = true }
            }
            if (!attribute.foregroundOnly) {
                ColorRow(
                    stringResource(Res.string.colors_background),
                    resolved.background,
                    onClear = if (override.background != null) ({ onChange(override.copy(background = null)) }) else null,
                ) { pickingBackground = true }
            }

            if (attribute.fontStyled) {
                SettingsDivider()
                SettingsToggleRow(stringResource(Res.string.colors_bold), null, resolved.bold == true) {
                    onChange(override.copy(bold = it))
                }
                SettingsToggleRow(stringResource(Res.string.colors_italic), null, resolved.italic == true) {
                    onChange(override.copy(italic = it))
                }
                SettingsToggleRow(stringResource(Res.string.colors_underline), null, resolved.underline == true) {
                    onChange(override.copy(underline = it))
                }
                SettingsToggleRow(stringResource(Res.string.colors_strikethrough), null, resolved.strikethrough == true) {
                    onChange(override.copy(strikethrough = it))
                }
            }

            if (parentTitle != null) {
                SettingsDivider()
                SettingsToggleRow(
                    stringResource(Res.string.colors_inherit, parentTitle),
                    stringResource(Res.string.colors_inherit_desc),
                    override.inheritParent,
                ) { onChange(override.copy(inheritParent = it)) }
            }

            SettingsDivider()
            SettingsActionRow(
                stringResource(Res.string.colors_reset_entry), null,
                stringResource(Res.string.colors_reset), destructive = false,
            ) { onReset() }
        }
    }

    ColorPickerDialog(
        visible = pickingForeground,
        initial = (resolved.foreground ?: Color.White).toArgbLong(),
        onDismiss = { pickingForeground = false },
        allowAlpha = true,
    ) {
        onChange(override.copy(foreground = Color(it)))
        pickingForeground = false
    }
    ColorPickerDialog(
        visible = pickingBackground,
        initial = (resolved.background ?: Color.Black).toArgbLong(),
        onDismiss = { pickingBackground = false },
        // A fill behind code is nearly always translucent — an opaque one hides the current-line tint and
        // the selection under it.
        allowAlpha = true,
    ) {
        onChange(override.copy(background = Color(it)))
        pickingBackground = false
    }
}

@Composable
private fun ColorRow(label: String, color: Color?, onClear: (() -> Unit)?, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                color?.toHex() ?: stringResource(Res.string.colors_background_none),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (onClear != null) ActionChip(stringResource(Res.string.colors_clear), onClick = onClear)
        val shape = RoundedCornerShape(Ca.radius.control)
        Box(
            Modifier
                .size(38.dp)
                .background(color ?: Color.Transparent, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onPick),
        )
    }
}

// ---- small shared pieces -------------------------------------------------------------------------------

/** How long an acknowledgement stays up before clearing itself. */
private const val NOTICE_MS = 2600L

@Composable
private fun noticeText(notice: SchemeNotice): String = when (notice) {
    SchemeNotice.Copied -> stringResource(Res.string.colors_copied)
    SchemeNotice.ImportFailed -> stringResource(Res.string.colors_import_failed)
    is SchemeNotice.Imported -> stringResource(Res.string.colors_imported, notice.name)
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SampleTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        Modifier
            .background(fill, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TextPromptDialog(
    visible: Boolean,
    title: String,
    initial: String,
    placeholder: String = "",
    multiline: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(visible, initial) { mutableStateOf(initial) }
    CenteredDialog(visible, onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    text, { text = it },
                    singleLine = !multiline,
                    maxLines = if (multiline) 6 else 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cancel)) }
                Button(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.save)) }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    visible: Boolean,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    CenteredDialog(visible, onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cancel)) }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmLabel) }
            }
        }
    }
}
