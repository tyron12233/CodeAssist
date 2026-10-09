package dev.ide.ui.editor.blocks

import androidx.compose.foundation.background
import dev.ide.ui.theme.Ca
import dev.ide.ui.generated.resources.add
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.CaMenuItem
import dev.ide.ui.components.CaDropdownMenu
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.block_add_event
import dev.ide.ui.generated.resources.block_add_function
import dev.ide.ui.generated.resources.block_add_variable
import dev.ide.ui.generated.resources.block_event_hint
import dev.ide.ui.generated.resources.block_event_loading
import dev.ide.ui.generated.resources.block_event_none
import dev.ide.ui.generated.resources.block_event_title
import dev.ide.ui.generated.resources.block_expression_body
import dev.ide.ui.generated.resources.block_form_add_param
import dev.ide.ui.generated.resources.block_form_annotations
import dev.ide.ui.generated.resources.block_form_edit_function
import dev.ide.ui.generated.resources.block_form_edit_variable
import dev.ide.ui.generated.resources.block_form_initial
import dev.ide.ui.generated.resources.block_form_invalid_name
import dev.ide.ui.generated.resources.block_form_modifiers
import dev.ide.ui.generated.resources.block_form_mutable
import dev.ide.ui.generated.resources.block_form_name
import dev.ide.ui.generated.resources.block_form_new_function
import dev.ide.ui.generated.resources.block_form_new_variable
import dev.ide.ui.generated.resources.block_form_param_name
import dev.ide.ui.generated.resources.block_form_param_type
import dev.ide.ui.generated.resources.block_form_params
import dev.ide.ui.generated.resources.block_form_place
import dev.ide.ui.generated.resources.block_form_readonly
import dev.ide.ui.generated.resources.block_form_return
import dev.ide.ui.generated.resources.block_form_type
import dev.ide.ui.generated.resources.block_functions
import dev.ide.ui.generated.resources.block_kind_accessor
import dev.ide.ui.generated.resources.block_kind_constructor
import dev.ide.ui.generated.resources.block_kind_init
import dev.ide.ui.generated.resources.block_no_members
import dev.ide.ui.generated.resources.block_outline_empty
import dev.ide.ui.generated.resources.block_top_level
import dev.ide.ui.generated.resources.block_variables
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.save
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import dev.ide.ui.generated.resources.block_block_count

/**
 * The Blocks view's first screen: what the file declares, as a flat list grouped by class or object
 * (top-level first). A function row opens it as blocks; a variable row opens its form. Each group's `+`
 * adds a function, a variable or, for a class, an event (an override of an inherited method).
 */
@Composable
internal fun OutlineScreen(
    outline: FileOutline,
    openKey: String?,
    onOpen: (OutlineFunction) -> Unit,
    onAddFunction: (OutlineGroup) -> Unit,
    onAddVariable: (OutlineGroup) -> Unit,
    onAddEvent: (OutlineGroup) -> Unit,
    onEditVariable: (OutlineVariable) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDeleteVariable: (OutlineVariable) -> Unit,
    ink: BlockInk,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.background(MaterialTheme.colorScheme.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 24.dp),
    ) {
        if (outline.groups.all { it.functions.isEmpty() && it.variables.isEmpty() }) {
            item {
                Text(
                    stringResource(Res.string.block_outline_empty), color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
        }
        outline.groups.forEachIndexed { gi, group ->
            item(key = "h:" + group.key) {
                GroupHeader(group, first = gi == 0, onAddFunction, onAddVariable, onAddEvent)
            }
            if (group.functions.isEmpty() && group.variables.isEmpty()) {
                item(key = "e:" + group.key) {
                    Text(
                        stringResource(Res.string.block_no_members), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 12.dp + (group.depth * 12).dp, top = 2.dp, bottom = 6.dp),
                    )
                }
            }
            items(group.functions, key = { "f:" + it.key }) { fn ->
                FunctionRow(fn, outline.kotlin, fn.key == openKey, group.depth, ink) { onOpen(fn) }
            }
            items(group.variables, key = { "v:" + group.key + ":" + it.name + ":" + it.node.start }) { v ->
                VariableRow(v, outline.kotlin, group.depth, ink) { onEditVariable(v) }
            }
        }
    }
}

/** A group's title line: `class MainActivity` (or "Top level") with the group's add menu. */
@Composable
private fun GroupHeader(
    group: OutlineGroup, first: Boolean,
    onAddFunction: (OutlineGroup) -> Unit, onAddVariable: (OutlineGroup) -> Unit, onAddEvent: (OutlineGroup) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column {
        if (!first) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(1.dp).background(scheme.outlineVariant.copy(alpha = 0.5f)))
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp + (group.depth * 12).dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val kind = when (group.kind) {
                GroupKind.TopLevel -> null
                GroupKind.Class -> "class"
                GroupKind.Interface -> "interface"
                GroupKind.Object -> "object"
                GroupKind.Enum -> "enum"
                GroupKind.Companion -> null
            }
            if (kind != null) {
                Text(kind, style = Ide.type.codeSmall, color = scheme.outline)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                group.title.ifEmpty { stringResource(Res.string.block_top_level) },
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (group.body != null) {
                Box {
                    IconButtonCa(CaIcons.plus, stringResource(Res.string.add), { menu = true }, iconSize = 16, boxSize = 30)
                    CaDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, iconLed = true) {
                        CaMenuItem(stringResource(Res.string.block_add_function), { menu = false; onAddFunction(group) }, icon = CaIcons.braces)
                        CaMenuItem(stringResource(Res.string.block_add_variable), { menu = false; onAddVariable(group) }, icon = CaIcons.docText)
                        if (group.kind == GroupKind.Class || group.kind == GroupKind.Object || group.kind == GroupKind.Enum) {
                            CaMenuItem(stringResource(Res.string.block_add_event), { menu = false; onAddEvent(group) }, icon = CaIcons.sparkle)
                        }
                    }
                }
            }
        }
    }
}

/** A header split for display: its leading tags (`override`, `@Composable`), and its parameters and result. */
private class HeaderParts(val tags: List<String>, val shape: String)

private fun headerParts(fn: OutlineFunction, kotlin: Boolean): HeaderParts {
    val sig = fn.signature
    // The parameter list follows the name (an annotation's own arguments, `@Preview(...)`, come before it).
    val named = Regex("\\b" + Regex.escape(fn.name.substringBefore(' ')) + "\\s*(<[^>]*>)?\\s*\\(").findAll(sig).lastOrNull()
    val open = named?.let { it.range.last } ?: sig.indexOf('(')
    if (open < 0) return HeaderParts(emptyList(), "")
    var depth = 0
    var close = sig.length - 1
    for (i in open until sig.length) {
        when (sig[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { close = i; break } } }
    }
    val head = sig.substring(0, open).trim()
    val params = sig.substring(open, close + 1)
    val tail = sig.substring(close + 1).trim()
    val words = Regex("@[\\w.]+(\\([^)]*\\))?|\\S+").findAll(head).map { it.value }.toList()
    val nameAt = words.indexOfLast { it.endsWith(fn.name.substringBefore(' ')) }
    val tags = ArrayList<String>()
    var result = tail.removePrefix(":").trim()
    words.forEachIndexed { i, w ->
        when {
            i == nameAt || w == "fun" || w == "public" -> {}
            !kotlin && i == nameAt - 1 && !w.startsWith("@") -> result = w
            else -> tags += w
        }
    }
    val shape = if (result.isEmpty() || result == "void" || result == "Unit") params else "$params: $result"
    return HeaderParts(tags, shape)
}

@Composable
private fun FunctionRow(fn: OutlineFunction, kotlin: Boolean, open: Boolean, depth: Int, ink: BlockInk, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val parts = remember(fn.signature) { headerParts(fn, kotlin) }
    val kind = when (fn.kind) {
        FunctionKind.Init -> stringResource(Res.string.block_kind_init)
        FunctionKind.Constructor -> stringResource(Res.string.block_kind_constructor)
        FunctionKind.Accessor -> stringResource(Res.string.block_kind_accessor)
        FunctionKind.Function -> null
    }
    val count = if (fn.statements < 0) stringResource(Res.string.block_expression_body)
    else pluralStringResource(Res.plurals.block_block_count, fn.statements, fn.statements).removePrefix("\u00b7 ")
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 12).dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(if (open) scheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HatGlyph(if ("@Composable" in fn.signature) ink.compose else ink.method)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name is measured first; the tags take what is left and ellipsize.
                Text(
                    fn.name, style = Ide.type.code, fontWeight = FontWeight.Medium,
                    color = if (open) scheme.onSecondaryContainer else scheme.onSurface, maxLines = 1,
                )
                val tags = (listOfNotNull(kind) + parts.tags.map { it.substringBefore('(') }).joinToString("  ")
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(tags, style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (parts.shape.isNotEmpty() && parts.shape != "()") {
                val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
                val syntax = Ide.colors.syntax
                Text(
                    remember(parts.shape, syntax) { dev.ide.ui.editor.highlight(parts.shape, lang, syntax) },
                    style = Ide.type.codeSmall, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = 0.8f },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(count, style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1)
    }
}

@Composable
private fun VariableRow(v: OutlineVariable, kotlin: Boolean, depth: Int, ink: BlockInk, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val keyword = if (kotlin) v.keyword else ""
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 12).dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(Ca.radius.sm)).clickable(onClick = onEdit)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).height(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(18.dp).height(11.dp).clip(RoundedCornerShape(50)).background(ink.data))
        }
        Spacer(Modifier.width(10.dp))
        val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
        val syntax = Ide.colors.syntax
        Text(
            buildAnnotatedString {
                if (keyword.isNotEmpty()) withStyle(SpanStyle(color = syntax.keyword.copy(alpha = 0.8f))) { append(keyword); append(' ') }
                withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) { append(v.name) }
                withStyle(SpanStyle(color = scheme.outline)) {
                    v.type?.let { append(": "); append(dev.ide.ui.editor.highlight(it, lang, syntax)) }
                    v.initializer?.let { append(" = "); append(dev.ide.ui.editor.highlight(it.replace(Regex("\\s+"), " "), lang, syntax)) }
                }
            },
            style = Ide.type.code, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        val mods = v.modifiers.split(' ').filter { it.isNotBlank() && it != "public" }.take(2).joinToString(" ")
        if (mods.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(mods, style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1)
        }
    }
}

/** A function's mark: a tiny hat block in the method color, the shape its page opens on. */
@Composable
private fun HatGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.width(20.dp).height(16.dp)) {
        val w = size.width
        val h = size.height
        // A cap: a rounded top over a flat base with the small connector a stack hangs from.
        val base = h * 0.82f
        val r = h * 0.5f
        val c = w * 0.2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, base)
            lineTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            lineTo(w - r, 0f)
            quadraticTo(w, 0f, w, r)
            lineTo(w, base)
            lineTo(c + w * 0.22f, base)
            lineTo(c + w * 0.17f, h)
            lineTo(c + w * 0.05f, h)
            lineTo(c, base)
            close()
        }
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------------------
// Forms.
// ---------------------------------------------------------------------------

private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun FormButtons(canSave: Boolean, onSave: () -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
        TextButton(onClick = onSave, enabled = canSave) { Text(stringResource(Res.string.save)) }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), error: Boolean = false) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, isError = error, textStyle = Ide.type.code, modifier = modifier)
}

/** What a form field holds, which decides where its completion comes from. */
enum class FieldKind { Type, Annotation, Value, Modifier }

/** A form completion: the field's whole new [text], and the edits (an import) accepting it needs. */
class Suggestion(val label: String, val detail: String?, val text: String, val extra: List<UiTextEdit>)

/** Asks for suggestions for a field of a kind, holding some text, in a group (class or top level). */
typealias Suggest = suspend (FieldKind, String, String) -> List<Suggestion>

/**
 * Names a value of [type] could take, matching what is [typed]: the type's own name and its last word in
 * camel case (`savedInstanceState: Bundle?` offers `bundle`), pluralized for a collection (`List<User>`
 * offers `users`, `userList`).
 */
fun nameSuggestions(type: String, typed: String): List<Suggestion> {
    val t = type.trim().removeSuffix("?")
    if (t.isEmpty()) return emptyList()
    val base = t.substringBefore('<').substringAfterLast('.').trim()
    val arg = t.substringAfter('<', "").substringBeforeLast('>', "").substringBefore(',').substringAfterLast('.').trim().removeSuffix("?")
    fun lower(s: String) = s.replaceFirstChar { it.lowercaseChar() }
    val words = Regex("[A-Z][a-z0-9]*|[a-z0-9]+").findAll(base).map { it.value }.toList()
    val names = buildList {
        val collection = base in setOf("List", "MutableList", "Set", "MutableSet", "Collection", "Iterable", "ArrayList", "Array")
        if (collection && arg.isNotEmpty()) { add(lower(arg) + "s"); add(lower(arg) + base.removePrefix("Mutable").removePrefix("Array").ifEmpty { "List" }) }
        add(lower(base))
        if (words.size > 1) add(lower(words.last()))
    }.filter { it.isNotEmpty() && it != typed && it.startsWith(typed, ignoreCase = true) && it !in KEYWORDS }.distinct()
    return names.map { Suggestion(it, null, it, emptyList()) }
}

private val KEYWORDS = setOf("int", "long", "short", "byte", "float", "double", "boolean", "char", "string", "object", "any", "unit")

/**
 * A form field with completion: while it has focus, what is typed is completed as [kind] (types, annotations,
 * values or modifiers) in the [group] the declaration goes into, and picking an item replaces the field and
 * hands its import edits to [onExtra].
 */
@Composable
internal fun SuggestField(
    label: String, value: String, onValue: (String) -> Unit, kind: FieldKind, group: String, suggest: Suggest,
    onExtra: (List<UiTextEdit>) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), error: Boolean = false,
    /** A one-line code box without a floating label, for dense rows (the modifier editor's arguments). */
    compact: Boolean = false,
    /** Highlight what is typed as code in this language. */
    language: dev.ide.ui.editor.CodeLanguage? = null,
) {
    val syntax = Ide.colors.syntax
    val transform = remember(language, syntax) { language?.let { highlightAs(it, syntax) } ?: androidx.compose.ui.text.input.VisualTransformation.None }
    var focused by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var accepted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value, focused, group) {
        if (!focused || value == accepted) { items = emptyList(); return@LaunchedEffect }
        delay(140)
        items = runCatching { suggest(kind, value, group) }.getOrDefault(emptyList())
    }
    Column(modifier) {
        if (compact) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (value.isEmpty()) Text(label, style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.outline)
                androidx.compose.foundation.text.BasicTextField(
                    value, onValue, singleLine = true,
                    textStyle = Ide.type.codeSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transform,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
        } else OutlinedTextField(
            value, onValue, label = { Text(label) }, singleLine = true, isError = error, textStyle = Ide.type.code,
            visualTransformation = transform,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused && items.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(vertical = 4.dp),
            ) {
                for (item in items.take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            accepted = item.text
                            onValue(item.text)
                            if (item.extra.isNotEmpty()) onExtra(item.extra)
                            items = emptyList()
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(item.label, style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        item.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** Choose which class/object (or the top level) a new declaration goes into. */
@Composable
private fun PlacePicker(groups: List<OutlineGroup>, selected: String, localPlace: String? = null, onSelect: (String) -> Unit) {
    if (groups.size < 2 && localPlace == null) return
    Text(stringResource(Res.string.block_form_place), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val places = (if (localPlace != null) listOf(LOCAL_PLACE to localPlace) else emptyList()) + groups.map { it.key to it.title }
        for ((key, title) in places) {
            val on = key == selected
            Text(
                title.ifEmpty { stringResource(Res.string.block_top_level) },
                style = Ide.type.codeSmall,
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(key) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Add or edit a variable. [existing] is null for a new one; a declaration the form cannot faithfully rewrite
 * (accessors, a delegate, several names in one Java declaration) shows read-only with a pointer to the code.
 */
@Composable
internal fun VariableForm(
    existing: OutlineVariable?,
    kotlin: Boolean,
    groups: List<OutlineGroup>,
    group: String,
    suggest: Suggest,
    onSave: (VariableSpec, String, List<UiTextEdit>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** The label of the "local of the open function" place, offered first; null when no function is open. */
    localPlace: String? = null,
) {
    val extra = remember { mutableStateListOf<UiTextEdit>() }
    val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: "") }
    var init by remember { mutableStateOf(existing?.initializer ?: "") }
    var modifiers by remember { mutableStateOf(existing?.modifiers ?: if (kotlin || group == LOCAL_PLACE) "" else "private") }
    var mutable by remember { mutableStateOf(existing?.keyword == "var") }
    var place by remember { mutableStateOf(group) }
    val editable = existing?.editable != false
    val valid = IDENTIFIER.matches(name.trim()) && (kotlin || type.isNotBlank())
    FormDialog(stringResource(if (existing == null) Res.string.block_form_new_variable else Res.string.block_form_edit_variable), onDismiss) {
        if (!editable) {
            Text(stringResource(Res.string.block_form_readonly), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FormButtons(false, {}, onDismiss, onDelete)
            return@FormDialog
        }
        SuggestField(
            stringResource(Res.string.block_form_name), name, { name = it }, FieldKind.Value, place,
            { _, typed, _ -> nameSuggestions(type, typed) }, {}, error = name.isNotEmpty() && !IDENTIFIER.matches(name.trim()), language = lang,
        )
        if (name.isNotEmpty() && !IDENTIFIER.matches(name.trim())) {
            Text(stringResource(Res.string.block_form_invalid_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SuggestField(stringResource(Res.string.block_form_type), type, { type = it }, FieldKind.Type, place, suggest, { extra += it }, language = lang)
        SuggestField(stringResource(Res.string.block_form_initial), init, { init = it }, FieldKind.Value, place, suggest, { extra += it }, language = lang)
        SuggestField(stringResource(Res.string.block_form_modifiers), modifiers, { modifiers = it }, FieldKind.Modifier, place, suggest, { extra += it }, language = lang)
        if (kotlin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(mutable, { mutable = it })
                Text(stringResource(Res.string.block_form_mutable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (existing == null) PlacePicker(groups, place, localPlace) { place = it }
        FormButtons(
            valid,
            { onSave(VariableSpec(modifiers, if (kotlin) (if (mutable) "var" else "val") else "", name, type, init), place, extra.distinct()) },
            onDismiss, onDelete,
        )
    }
}

/** Add or edit a function's header: name, parameters, return type, modifiers and annotations. */
@Composable
internal fun FunctionForm(
    existing: FunctionSpec?,
    editing: Boolean,
    kotlin: Boolean,
    groups: List<OutlineGroup>,
    group: String,
    suggest: Suggest,
    onSave: (FunctionSpec, String, List<UiTextEdit>) -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = remember { mutableStateListOf<UiTextEdit>() }
    val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var returnType by remember { mutableStateOf(existing?.returnType ?: "") }
    var modifiers by remember { mutableStateOf(existing?.modifiers ?: if (kotlin) "" else "private") }
    var annotations by remember { mutableStateOf(existing?.annotations ?: "") }
    val params = remember { mutableStateListOf<ParamSpec>().apply { addAll(existing?.params ?: emptyList()) } }
    var place by remember { mutableStateOf(group) }
    val valid = IDENTIFIER.matches(name.trim()) && params.all { it.name.isBlank() || IDENTIFIER.matches(it.name.trim()) }
    FormDialog(stringResource(if (editing) Res.string.block_form_edit_function else Res.string.block_form_new_function), onDismiss) {
        Field(stringResource(Res.string.block_form_name), name, { name = it }, error = name.isNotEmpty() && !IDENTIFIER.matches(name.trim()))
        Text(stringResource(Res.string.block_form_params), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        params.forEachIndexed { i, p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                SuggestField(
                    stringResource(Res.string.block_form_param_name), p.name, { params[i] = params[i].copy(name = it) },
                    FieldKind.Value, place, { _, typed, _ -> nameSuggestions(params[i].type, typed) }, {}, Modifier.weight(1f), language = lang,
                )
                SuggestField(stringResource(Res.string.block_form_param_type), p.type, { params[i] = params[i].copy(type = it) }, FieldKind.Type, place, suggest, { extra += it }, Modifier.weight(1f), language = lang)
                Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp).clickable { params.removeAt(i) }, tint = MaterialTheme.colorScheme.outline)
            }
        }
        TextButton(onClick = { params += ParamSpec("", "") }) { Text(stringResource(Res.string.block_form_add_param)) }
        SuggestField(stringResource(Res.string.block_form_return), returnType, { returnType = it }, FieldKind.Type, place, suggest, { extra += it }, language = lang)
        SuggestField(stringResource(Res.string.block_form_modifiers), modifiers, { modifiers = it }, FieldKind.Modifier, place, suggest, { extra += it }, language = lang)
        SuggestField(stringResource(Res.string.block_form_annotations), annotations, { annotations = it }, FieldKind.Annotation, place, suggest, { extra += it }, language = lang)
        if (!editing) PlacePicker(groups, place) { place = it }
        FormButtons(valid, { onSave(FunctionSpec(annotations, modifiers, name.trim(), params.toList(), returnType), place, extra.distinct()) }, onDismiss)
    }
}

/** A method the class may override, offered by completion: its label, origin and the stub that adds it. */
class EventChoice(val label: String, val detail: String, val apply: () -> Unit)

/** Pick an inherited method to override (an "event", in Sketchware's terms); [choices] is null while loading. */
@Composable
internal fun EventPicker(choices: List<EventChoice>?, onDismiss: () -> Unit) {
    FormDialog(stringResource(Res.string.block_event_title), onDismiss) {
        Text(stringResource(Res.string.block_event_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            choices == null -> Text(stringResource(Res.string.block_event_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            choices.isEmpty() -> Text(stringResource(Res.string.block_event_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            else -> for (c in choices) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { c.apply() }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(CaIcons.sparkle, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = Ide.type.code, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    }
}
