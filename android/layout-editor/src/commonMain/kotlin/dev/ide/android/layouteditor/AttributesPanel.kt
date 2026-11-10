package dev.ide.android.layouteditor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.AndroidLayoutModel
import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutValueCandidate
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The selected view's attributes. The common ones for its kind come first, each with the control its format
 * calls for (chips for a size or an enum, a switch for a boolean, a swatch for a color, a completing field for
 * everything else); every other attribute the view accepts is one tap further, searchable.
 *
 * What a view accepts, and what a value may complete to, come from the host's layout model: the same SDK,
 * library and project metadata the XML editor completes from. Edits are computed by the model against the
 * live buffer and applied through [onEdit], so each one is an undo step in the code view.
 */
@Composable
internal fun AttributesPanel(
    path: String,
    element: LayoutElement,
    model: AndroidLayoutModel,
    canEdit: Boolean,
    textProvider: () -> String,
    /** Applies an edit to [element]; the compute gets the buffer and where [element] is in it now. */
    onEdit: (compute: suspend (text: String, ref: ElementRef) -> List<LayoutEdit>) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier,
) {
    val parentTag = element.parent?.tag
    var catalog by remember(element.tag, parentTag) { mutableStateOf<List<LayoutAttributeInfo>?>(null) }
    LaunchedEffect(element.tag, parentTag) {
        catalog = runCatching { model.attributes(path, element.tag, parentTag) }.getOrDefault(emptyList())
    }
    val byName = remember(catalog) { catalog.orEmpty().associateBy { it.name } }
    // The attribute being added: its value is asked for in a dialog, and nothing is written until there is one
    // (an empty attribute would break the render).
    var adding by remember(element.start, element.tag) { mutableStateOf<String?>(null) }
    var allOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val ref = element.ref

    fun set(name: String, value: String) = onEdit { text, at -> model.setAttribute(path, text, at, name, value) }
    fun remove(name: String) = onEdit { text, at -> model.removeAttribute(path, text, at, name) }
    suspend fun complete(name: String, field: String, caret: Int) =
        model.completeValue(path, textProvider(), ref, name, field, caret)

    val setNames = element.attributes.map { it.name }.filterNot { it == "xmlns" || it.startsWith("xmlns:") }
    val common = remember(element.tag, catalog, setNames) {
        commonAttributesFor(element.tag).filter { it in byName || it in setNames }
    }

    adding?.let { name ->
        EditorDialog(
            glyph = Sym.add,
            title = name.substringAfter(':'),
            attribute = name,
            onDismiss = { adding = null },
        ) {
            AttributeRow(
                name, null, byName[name], canEdit,
                onSet = { if (it.isNotBlank()) { set(name, it); adding = null } },
                onRemove = { adding = null },
            ) { f, c -> complete(name, f, c) }
        }
    }

    Column(modifier) {
        // Header: what is selected.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Symbol(glyphFor(element.tag), null, size = 22.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer, filled = true)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(element.simpleName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The id, else the fully-qualified tag when it says more than the title; nothing when it would repeat it.
                val subtitle = element.attribute("android:id")?.value ?: element.tag.takeIf { it != element.simpleName }
                if (subtitle != null) Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClose != null) BarButton(Sym.close, stringResource(Res.string.le_close), onClick = onClose)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel(stringResource(Res.string.le_attrs_common))
            if (catalog == null) {
                Text(stringResource(Res.string.le_attrs_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (name in common) {
                AttributeRow(
                    name = name,
                    value = element.attribute(name)?.value,
                    info = byName[name],
                    enabled = canEdit,
                    onSet = { set(name, it) },
                    onRemove = { remove(name) },
                    complete = { f, c -> complete(name, f, c) },
                )
            }

            // Everything else the view accepts.
            val others = setNames.filter { it !in common }
            val addable = catalog.orEmpty().map { it.name }.filter { it !in setNames && it !in common }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { allOpen = !allOpen }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(Res.string.le_attrs_all), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                CountBadge(others.size + addable.size)
                Box(Modifier.weight(1f))
                Symbol(if (allOpen) Sym.unfoldLess else Sym.expandMore, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (allOpen) {
                SearchField(query, { query = it }, stringResource(Res.string.le_attrs_search))
                val q = query.trim().lowercase()
                fun matches(n: String) = q.isEmpty() || n.lowercase().contains(q)
                for (name in others.filter(::matches)) {
                    AttributeRow(name, element.attribute(name)?.value, byName[name], canEdit, { set(name, it) }, { remove(name) }) { f, c -> complete(name, f, c) }
                }
                val shownAddable = addable.filter(::matches).take(if (q.isEmpty()) 40 else 200)
                if (shownAddable.isNotEmpty()) SectionLabel(stringResource(Res.string.le_attrs_add))
                for (name in shownAddable) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = canEdit) { adding = name }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Symbol(Sym.add, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                        Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The attributes shown up front for a view of [tag]: size and id always, then what that kind is about. */
internal fun commonAttributesFor(tag: String): List<String> {
    val n = tag.substringAfterLast('.')
    val head = listOf("android:id", "android:layout_width", "android:layout_height")
    val kind = when {
        n.endsWith("TextInputLayout") -> listOf("android:hint", "app:endIconMode", "app:helperText")
        n.endsWith("EditText") -> listOf("android:hint", "android:inputType", "android:text")
        n.contains("Switch") || n.endsWith("CheckBox") || n.endsWith("RadioButton") -> listOf("android:text", "android:checked")
        n.endsWith("ImageButton") || n.endsWith("FloatingActionButton") -> listOf("app:srcCompat", "android:src", "android:contentDescription")
        n.endsWith("Button") || n.endsWith("Chip") -> listOf("android:text", "android:enabled", "app:icon", "app:backgroundTint")
        n.endsWith("ImageView") -> listOf("app:srcCompat", "android:src", "android:scaleType", "android:contentDescription", "app:tint")
        n.endsWith("TextView") -> listOf("android:text", "android:textAppearance", "android:textSize", "android:textColor", "android:textStyle")
        n == "LinearLayout" || n == "LinearLayoutCompat" -> listOf("android:orientation", "android:gravity")
        n.endsWith("ProgressBar") || n.endsWith("SeekBar") -> listOf("android:max", "android:progress", "android:indeterminate")
        else -> emptyList()
    }
    val tail = listOf("android:visibility", "android:background", "android:padding", "android:layout_margin", "android:layout_gravity", "android:layout_weight")
    return (head + kind + tail).distinct()
}

/** How an attribute's value is entered. */
private enum class AttrKind { ID, BOOLEAN, DIMENSION, FLAGS, ENUM, COLOR, TEXT }

private fun kindOf(name: String, info: LayoutAttributeInfo?): AttrKind {
    if (name == "android:id") return AttrKind.ID
    val f = info?.formats.orEmpty()
    return when {
        info == null -> AttrKind.TEXT
        "boolean" in f && info.resourceTypes.all { it == "bool" } -> AttrKind.BOOLEAN
        "dimension" in f -> AttrKind.DIMENSION
        info.flagValues.isNotEmpty() -> AttrKind.FLAGS
        info.enumValues.isNotEmpty() -> AttrKind.ENUM
        "color" in f && f.all { it == "color" || it == "reference" } && info.resourceTypes.all { it == "color" } -> AttrKind.COLOR
        else -> AttrKind.TEXT
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeRow(
    name: String,
    value: String?,
    info: LayoutAttributeInfo?,
    enabled: Boolean,
    onSet: (String) -> Unit,
    onRemove: () -> Unit,
    complete: suspend (field: String, caret: Int) -> dev.ide.android.layout.LayoutValueCompletion,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name.substringAfter(':'),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null && name != "android:layout_width" && name != "android:layout_height") {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClickLabel = stringResource(Res.string.le_attr_remove), onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) { Symbol(Sym.close, stringResource(Res.string.le_attr_remove), size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val v = value.orEmpty()
        when (kindOf(name, info)) {
            AttrKind.ID -> ValueField(
                initial = v.substringAfter('/'),
                enabled = enabled,
                placeholder = stringResource(Res.string.le_attr_id_hint),
                complete = null,
                onCommit = { if (it.isBlank()) onRemove() else onSet("@+id/" + it.trim().substringAfter('/')) },
            )
            // Two chips rather than a switch: an unset boolean has the view's default, which a switch would
            // misstate as "false" (an unset `enabled` is true).
            AttrKind.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in listOf("true", "false")) ChoiceChip(b, selected = v == b, enabled = enabled) { onSet(b) }
            }
            AttrKind.DIMENSION -> {
                // wrap_content first, as the design (and every layout editor) lists them.
                // `fill_parent` is the deprecated spelling of `match_parent`; offering both shows "match" twice.
                val keywords = info?.enumValues.orEmpty()
                    .let { ks -> if ("match_parent" in ks) ks - "fill_parent" else ks }
                    .sortedBy { if (it == "wrap_content") 0 else 1 }
                if (keywords.isNotEmpty()) {
                    var customMode by remember(v) { mutableStateOf(v.isNotEmpty() && v !in keywords) }
                    SegmentedChoice(
                        options = keywords.map { shortKeyword(it) } + stringResource(Res.string.le_attr_custom),
                        selected = if (customMode) keywords.size else keywords.indexOf(v),
                        enabled = enabled,
                        onSelect = { i -> if (i < keywords.size) { customMode = false; onSet(keywords[i]) } else customMode = true },
                    )
                    if (customMode) {
                        ValueField(v.takeIf { it !in keywords }.orEmpty(), enabled, stringResource(Res.string.le_attr_dimension_hint), complete, onSet)
                    }
                } else {
                    ValueField(v, enabled, stringResource(Res.string.le_attr_dimension_hint), complete, onSet)
                }
            }
            AttrKind.ENUM -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in info?.enumValues.orEmpty()) ChoiceChip(e, selected = v == e, enabled = enabled) { onSet(e) }
            }
            AttrKind.FLAGS -> {
                val on = v.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in info?.flagValues.orEmpty()) ChoiceChip(f, selected = f in on, enabled = enabled) {
                        val next = if (f in on) on - f else on + f
                        if (next.isEmpty()) onRemove() else onSet(info!!.flagValues.filter { it in next }.joinToString("|"))
                    }
                }
            }
            AttrKind.COLOR -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(parseHexColor(v) ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                )
                Box(Modifier.weight(1f)) { ValueField(v, enabled, stringResource(Res.string.le_attr_color_hint), complete, onSet) }
            }
            AttrKind.TEXT -> ValueField(v, enabled, info?.resourceTypes?.firstOrNull()?.let { "@$it/…" } ?: "", complete, onSet)
        }
    }
}

internal fun shortKeyword(k: String): String = when (k) {
    "wrap_content" -> "wrap"
    "match_parent", "fill_parent" -> "match"
    else -> k
}

@Composable
internal fun SegmentedChoice(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp)
                options.lastIndex -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp)
                else -> RoundedCornerShape(6.dp)
            }
            Row(
                Modifier.weight(1f).height(40.dp).clip(shape).background(bg).clickable(enabled = enabled) { onSelect(i) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (on) Symbol(Sym.check, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (on) 4.dp else 0.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) Symbol(Sym.check, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A value field that completes like the XML editor: candidates from the host's layout model as the user types,
 * committed on Done, on focus loss or on a pick. Only a real change is committed.
 */
@Composable
internal fun ValueField(
    initial: String,
    enabled: Boolean,
    placeholder: String,
    complete: (suspend (field: String, caret: Int) -> dev.ide.android.layout.LayoutValueCompletion)?,
    onCommit: (String) -> Unit,
) {
    var field by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var focused by remember { mutableStateOf(false) }
    var everFocused by remember(initial) { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<LayoutValueCandidate>()) }
    var replace by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(field.text, field.selection, focused) {
        if (!focused || complete == null) { candidates = emptyList(); return@LaunchedEffect }
        delay(120)
        val r = runCatching { complete(field.text, field.selection.start) }.getOrNull() ?: return@LaunchedEffect
        candidates = r.candidates.take(8)
        replace = r.replaceStart to r.replaceEnd
    }
    fun commit(text: String) {
        candidates = emptyList()
        if (text != initial) onCommit(text)
    }
    val border by animateColorAsState(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.5.dp, border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(field.text) }),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (it.isFocused) everFocused = true
                    focused = it.isFocused
                    // The first callback fires unfocused at composition; only a real focus loss commits.
                    if (!it.isFocused && everFocused) commit(field.text)
                },
                decorationBox = { inner ->
                    if (field.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    inner()
                },
            )
        }
        if (focused && candidates.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                for (c in candidates) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val (s, e) = replace
                            val start = s.coerceIn(0, field.text.length)
                            val end = e.coerceIn(start, field.text.length)
                            val next = field.text.substring(0, start) + c.insertText + field.text.substring(end)
                            field = TextFieldValue(next, TextRange(start + c.insertText.length))
                            commit(next)
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        parseHexColor(c.detail.orEmpty())?.let {
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(it))
                        }
                        Text(c.label, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        c.detail?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, hint: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(Sym.search, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CountBadge(n: Int) {
    Text(
        n.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** `#RGB`, `#ARGB`, `#RRGGBB` or `#AARRGGBB` as a color; null for anything else (a reference). */
internal fun parseHexColor(value: String): Color? {
    if (!value.startsWith("#")) return null
    val hex = value.substring(1)
    val argb = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        4 -> hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it.toInt()) }
}
