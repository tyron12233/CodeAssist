package dev.ide.ui.editor.preview

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ide.preview.PreviewViewNode
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAttrKind
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiLayoutAttribute
import dev.ide.ui.backend.UiLayoutElement
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.CompletionList
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The real-view Preview's editable attribute editor: an adaptive surface (a draggable bottom sheet on a phone,
 * a docked side panel when wide) that edits the tapped view's layout XML source. The attributes it offers — and
 * their value completion — come from the SAME Android SDK metadata + resource index the XML editor uses, so only
 * attributes valid for the view can be added and value completion matches typing in the XML by hand.
 *
 * All edits are computed by the backend against [text] + [element]'s source offset and handed to [onEdit], which
 * applies them to the shared editor session (so the Code view + preview both update). [node] supplies the
 * read-only "Rendered" section (what the framework actually inflated).
 */
@Composable
internal fun BoxScope.LayoutAttributeSheet(
    element: UiLayoutElement,
    node: PreviewViewNode,
    path: String,
    text: String,
    backend: IdeBackend,
    onEdit: (List<UiTextEdit>) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Serialize edits: a commit holds `busy` until the fresh [element] arrives (offsets shift), so a second
    // edit can't be computed against a stale offset. Reset when [element] changes (a new object).
    var busy by remember(element) { mutableStateOf(false) }

    fun commit(name: String, value: String) {
        if (busy) return
        busy = true
        scope.launch {
            val edits = backend.preview.setLayoutAttribute(path, text, element.sourceOffset, element.id, name, value)
            if (edits.isEmpty()) busy = false else onEdit(edits)
        }
    }

    fun remove(name: String) {
        if (busy) return
        busy = true
        scope.launch {
            val edits = backend.preview.removeLayoutAttribute(path, text, element.sourceOffset, element.id, name)
            if (edits.isEmpty()) busy = false else onEdit(edits)
        }
    }

    suspend fun complete(name: String, fieldText: String, caret: Int) =
        backend.preview.completeLayoutAttributeValue(path, text, element.sourceOffset, element.id, name, fieldText, caret)

    // Compact vs docked is decided from the window size (no BoxWithConstraints wrapper — its full-size
    // SubcomposeLayout is the one structural difference from the known-working overlay panels; a directly
    // aligned bounded panel keeps child taps reliable).
    val windowInfo = LocalWindowInfo.current
    val widthDp = with(LocalDensity.current) { windowInfo.containerSize.width.toDp() }
    val fullHpx = windowInfo.containerSize.height.coerceAtLeast(1)
    val compact = widthDp < 560.dp

    // Entrance: slide + fade via a LEAF graphicsLayer (pointer input is transform-aware, so this doesn't
    // interfere with the controls, unlike an AnimatedVisibility wrapper).
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "sheetAppear")

    if (compact) {
        var fraction by remember { mutableStateOf(0.52f) }
        val drag = rememberDraggableState { deltaPx ->
            // Dragging the handle down (positive delta) shrinks the sheet.
            fraction = (fraction - deltaPx / fullHpx).coerceIn(0.24f, 0.92f)
        }
        SheetPanel(
            element, node, busy, ::commit, ::remove, ::complete, onClose,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fraction)
                .graphicsLayer { alpha = appear; translationY = (1f - appear) * 140f },
            topCornersOnly = true,
            handle = {
                Box(
                    Modifier.fillMaxWidth().height(26.dp).draggable(drag, Orientation.Vertical),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Ca.colors.separator))
                }
            },
        )
    } else {
        val maxHDp = with(LocalDensity.current) { fullHpx.toDp() } * 0.92f
        SheetPanel(
            element, node, busy, ::commit, ::remove, ::complete, onClose,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Ca.spacing.s3)
                .widthIn(min = 280.dp, max = 360.dp).heightIn(max = maxHDp)
                .graphicsLayer { alpha = appear; translationX = (1f - appear) * 80f },
            topCornersOnly = false,
            handle = null,
        )
    }
}

@Composable
private fun SheetPanel(
    element: UiLayoutElement,
    node: PreviewViewNode,
    busy: Boolean,
    commit: (String, String) -> Unit,
    remove: (String) -> Unit,
    complete: suspend (String, String, Int) -> UiCompletionResult,
    onClose: () -> Unit,
    modifier: Modifier,
    topCornersOnly: Boolean,
    handle: (@Composable () -> Unit)?,
) {
    val shape = if (topCornersOnly)
        RoundedCornerShape(topStart = Ca.radius.sheet, topEnd = Ca.radius.sheet)
    else RoundedCornerShape(Ca.radius.lg)
    Column(
        modifier
            .shadow(20.dp, shape)
            .clip(shape)
            .background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, shape)
            .imePadding(),
    ) {
        handle?.invoke()
        Header(element, node, onClose)
        HairLine()
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s3),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            AttributesSection(element, busy, commit, remove, complete)
            RenderedSection(node)
        }
    }
}

@Composable
private fun Header(element: UiLayoutElement, node: PreviewViewNode, onClose: () -> Unit) {
    val title = node.simpleName.ifEmpty { element.tag }
    Row(
        Modifier.fillMaxWidth().padding(start = Ca.spacing.s3, end = Ca.spacing.s2, top = Ca.spacing.s2, bottom = Ca.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        // Accent badge with the view's initial — a compact, recognizable leading glyph.
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(Ca.radius.control)).background(Ca.colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase(), color = Ca.colors.accent, style = Ca.type.subhead, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1)
            Text(
                element.id?.let { "@id/$it" } ?: element.tag,
                color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1,
            )
        }
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(Ca.colors.surface2).clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.close, "Close", Modifier.size(15.dp), tint = Ca.colors.textSecondary)
        }
    }
}

@Composable
private fun AttributesSection(
    element: UiLayoutElement,
    busy: Boolean,
    commit: (String, String) -> Unit,
    remove: (String) -> Unit,
    complete: suspend (String, String, Int) -> UiCompletionResult,
) {
    var adding by remember(element) { mutableStateOf(false) }
    // Attributes the user added but hasn't given a value to yet — kept LOCAL (never written to the layout), so an
    // empty `name=""` can't reach the render (which would error on most attributes and drop the preview). A draft
    // is written to source only once its value control produces a non-empty value; the element then re-fetches
    // and it moves into [element.setAttributes] (drafts reset on the new element).
    var drafts by remember(element) { mutableStateOf<List<UiLayoutAttribute>>(emptyList()) }
    val draftNames = drafts.mapTo(HashSet()) { it.name }
    val addable = element.addable.filterNot { it.name in draftNames }

    SectionLabel("Attributes", element.setAttributes.size + drafts.size)
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
        if (element.setAttributes.isEmpty() && drafts.isEmpty() && !adding) {
            Text("No attributes set", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(vertical = Ca.spacing.s1))
        }
        for (attr in element.setAttributes) {
            AttributeCard(attr, busy, onSet = { commit(attr.name, it) }, onRemove = { remove(attr.name) }, complete = complete)
        }
        // Draft (unsaved) attributes: entering a value writes them; leaving them empty touches nothing.
        for (draft in drafts) {
            AttributeCard(
                draft, busy,
                onSet = { v -> if (v.isNotBlank()) commit(draft.name, v) },
                onRemove = { drafts = drafts.filterNot { it.name == draft.name } },
                complete = complete,
            )
        }
        if (adding) {
            AddAttributeList(
                addable = addable,
                // Adding stages a DRAFT (no source write yet) so an empty value can't break the preview.
                onPick = { adding = false; if (it.name !in draftNames) drafts = drafts + it },
                onDismiss = { adding = false },
            )
        }
        if (!adding && addable.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md))
                    .background(Ca.colors.accent.copy(alpha = 0.12f))
                    .clickable(enabled = !busy) { adding = true }
                    .padding(vertical = Ca.spacing.s3, horizontal = Ca.spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Icon(CaIcons.plus, null, Modifier.size(17.dp), tint = Ca.colors.accent)
                Text("Add attribute", color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RenderedSection(node: PreviewViewNode) {
    val groups = remember(node) { node.properties.groupBy { it.group } }
    if (groups.isEmpty()) return
    SectionLabel("Rendered", null)
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        for ((group, props) in groups) {
            Text(group.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Ca.spacing.s1))
            for (p in props) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                Text(p.name, color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.width(104.dp), maxLines = 1)
                Text(p.value, color = Ca.colors.textPrimary, style = Ca.type.caption, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        Text(text.uppercase(), color = Ca.colors.textSecondary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        if (count != null && count > 0) Text(
            count.toString(), color = Ca.colors.textTertiary, style = Ca.type.caption2,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Ca.colors.surface2).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** One attribute: its name + a remove button, then the value control chosen by [UiLayoutAttribute.kind]. */
@Composable
private fun AttributeCard(
    attr: UiLayoutAttribute,
    busy: Boolean,
    onSet: (String) -> Unit,
    onRemove: () -> Unit,
    complete: suspend (String, String, Int) -> UiCompletionResult,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.surface2).padding(Ca.spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(attr.name, color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable(enabled = !busy) { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.close, "Remove", Modifier.size(13.dp), tint = Ca.colors.textTertiary)
            }
        }
        ValueControl(attr, busy, onSet, complete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueControl(
    attr: UiLayoutAttribute,
    busy: Boolean,
    onSet: (String) -> Unit,
    complete: suspend (String, String, Int) -> UiCompletionResult,
) {
    val value = attr.value ?: ""
    when (attr.kind) {
        UiAttrKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            val on = value == "true"
            Switch(
                checked = on, enabled = !busy, onCheckedChange = { onSet(if (it) "true" else "false") },
                colors = SwitchDefaults.colors(checkedTrackColor = Ca.colors.accent, checkedThumbColor = Color.White),
            )
            Text(if (on) "true" else "false", color = Ca.colors.textPrimary, style = Ca.type.footnote)
        }

        UiAttrKind.ENUM -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
            for (v in attr.enumValues) SelectChip(v, selected = value == v, enabled = !busy) { onSet(v) }
        }

        UiAttrKind.FLAGS -> {
            val selected = remember(value) { value.split('|').filter { it.isNotBlank() }.toSet() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
                for (v in attr.flagValues) SelectChip(v, selected = v in selected, enabled = !busy) {
                    val next = if (v in selected) selected - v else selected + v
                    onSet(attr.flagValues.filter { it in next }.joinToString("|"))
                }
            }
        }

        UiAttrKind.DIMENSION -> Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            if (attr.enumValues.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
                for (v in attr.enumValues) SelectChip(v, selected = value == v, enabled = !busy) { onSet(v) }
            }
            CompletionTextField(attr.name, value, busy, onCommit = onSet, complete = complete, placeholder = "e.g. 16dp")
        }

        UiAttrKind.COLOR -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(Ca.radius.sm))
                    .background(parseHexColor(value) ?: Ca.colors.surface3)
                    .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.sm)),
            )
            Box(Modifier.weight(1f)) { CompletionTextField(attr.name, value, busy, onCommit = onSet, complete = complete, placeholder = "#RRGGBB / @color/…") }
        }

        else -> CompletionTextField(attr.name, value, busy, onCommit = onSet, complete = complete)
    }
}

/** A selectable chip (enum single-select / flag toggle), with an animated selection colour. */
@Composable
private fun SelectChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accent else Ca.colors.surface, tween(150), label = "chipBg")
    val fg by animateColorAsState(if (selected) Color.White else Ca.colors.textPrimary, tween(150), label = "chipFg")
    val border by animateColorAsState(if (selected) Ca.colors.accent else Ca.colors.separator, tween(150), label = "chipBorder")
    Box(
        Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Ca.spacing.s3, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = Ca.type.footnote, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * A value text field with the SAME completion the XML editor gives (via the backend, over the live buffer).
 * Commits the value on Done / focus loss / completion pick; the popup narrows as the user types.
 */
@Composable
private fun CompletionTextField(
    attrName: String,
    initial: String,
    busy: Boolean,
    onCommit: (String) -> Unit,
    complete: suspend (String, String, Int) -> UiCompletionResult,
    placeholder: String = "",
) {
    var field by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var items by remember { mutableStateOf<List<UiCompletionItem>>(emptyList()) }
    var replace by remember { mutableStateOf(0 to 0) }
    var focused by remember { mutableStateOf(false) }
    // Whether the field has EVER held focus. onFocusChanged fires once at composition with `false`; without this
    // guard that spurious callback auto-commits every field's current value the instant the sheet appears — which
    // wedged `busy` true and disabled every control (the "can't edit anything" bug).
    var everFocused by remember(initial) { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }

    LaunchedEffect(field.text, field.selection, focused) {
        if (!focused) { items = emptyList(); return@LaunchedEffect }
        delay(120)
        val res = complete(attrName, field.text, field.selection.start)
        items = res.items
        replace = res.replaceStart to res.replaceEnd
        selectedIndex = 0
    }

    fun pick(item: UiCompletionItem) {
        val (s, e) = replace
        val cs = s.coerceIn(0, field.text.length)
        val ce = e.coerceIn(cs, field.text.length)
        val newText = field.text.substring(0, cs) + item.insertText + field.text.substring(ce)
        field = TextFieldValue(newText, TextRange((cs + item.insertText.length).coerceAtMost(newText.length)))
        items = emptyList()
        onCommit(newText)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        val borderColor by animateColorAsState(if (focused) Ca.colors.accent else Ca.colors.separator, tween(150), label = "fieldBorder")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm))
                .background(Ca.colors.surface)
                .border(1.dp, borderColor, RoundedCornerShape(Ca.radius.sm))
                .padding(horizontal = Ca.spacing.s3, vertical = 10.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { if (!busy) field = it },
                singleLine = true,
                enabled = !busy,
                textStyle = Ca.type.code.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                keyboardActions = KeyboardActions(onDone = { if (field.text != initial) onCommit(field.text); items = emptyList() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().onFocusChangedCommit { gained ->
                    if (gained) everFocused = true
                    focused = gained
                    // Commit only on a REAL focus loss (after being focused) and only when the value changed — the
                    // initial onFocusChanged(false) at composition must never auto-commit the current value.
                    if (!gained) {
                        items = emptyList()
                        if (everFocused && field.text != initial) onCommit(field.text)
                    }
                },
                decorationBox = { inner ->
                    if (field.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.code)
                    }
                    inner()
                },
            )
        }
        if (focused && items.isNotEmpty()) {
            CompletionList(
                items = items,
                selectedIndex = selectedIndex,
                prefix = field.text.substring(replace.first.coerceIn(0, field.text.length), field.selection.start.coerceIn(0, field.text.length)),
                width = 300.dp,
                onPick = { pick(it) },
                onHover = { selectedIndex = it },
                maxListHeight = 220.dp,
                docsBeside = false,
            )
        }
    }
}

/** A searchable list of the allowed-but-unset attributes — the only-valid-attributes add picker. */
@Composable
private fun AddAttributeList(addable: List<UiLayoutAttribute>, onPick: (UiLayoutAttribute) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, addable) {
        if (query.isBlank()) addable else addable.filter { nameMatches(it.name, query) }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.surface2)
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)).padding(Ca.spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add attribute", color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) { Icon(CaIcons.close, "Close", Modifier.size(13.dp), tint = Ca.colors.textTertiary) }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).background(Ca.colors.surface)
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = Ca.spacing.s3, vertical = 10.dp),
        ) {
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = Ca.type.code.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search attributes", color = Ca.colors.textTertiary, style = Ca.type.code)
                    inner()
                },
            )
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            for (a in filtered) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).clickable { onPick(a) }.padding(vertical = 9.dp, horizontal = Ca.spacing.s2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Text(a.name, color = Ca.colors.textPrimary, style = Ca.type.footnote, modifier = Modifier.weight(1f), maxLines = 1)
                valueHint(a)?.let {
                    Text(it, color = Ca.colors.textTertiary, style = Ca.type.caption2, modifier = Modifier.clip(RoundedCornerShape(50)).background(Ca.colors.surface).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
    }
}

@Composable
private fun HairLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

private fun valueHint(a: UiLayoutAttribute): String? = when (a.kind) {
    UiAttrKind.BOOLEAN -> "boolean"
    UiAttrKind.ENUM -> "enum"
    UiAttrKind.FLAGS -> "flags"
    UiAttrKind.DIMENSION -> "dimension"
    UiAttrKind.COLOR -> "color"
    UiAttrKind.REFERENCE -> a.resourceRClasses.joinToString("|") { "@$it" }.ifEmpty { null }
    else -> null
}

/** Namespace-aware substring match for the add picker (matches `text` against `android:layout_width`). */
private fun nameMatches(candidate: String, query: String): Boolean {
    val q = query.lowercase()
    val c = candidate.lowercase()
    return c.contains(q) || c.substringAfter(':').contains(q)
}

/** Parse `#RGB`/`#RRGGBB`/`#AARRGGBB` to a Compose color, or null (so the swatch shows a neutral fill). */
private fun parseHexColor(value: String): Color? {
    if (!value.startsWith("#")) return null
    val hex = value.substring(1)
    val argb = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return runCatching { Color(argb.toLong(16)) }.getOrNull()
}

/** onFocusChanged that reports gained/lost as a single boolean. */
private fun Modifier.onFocusChangedCommit(onChange: (Boolean) -> Unit): Modifier =
    onFocusChanged { onChange(it.isFocused) }
