package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiTextEdit

/*
 * Source text the block view writes: the snippet wrappers that let a loose scratch stack or a palette
 * template be projected like real code, the palette's templates, and the declarations the Add/Edit forms
 * produce. Java and Kotlin each get their own spelling.
 */

/** The body marker a C-block template carries where wrapped statements go. */
val BODY: String = UiBlockEdit.BODY_MARKER.toString()

/** A template without its body marker, as inserted on its own (the marker's line is dropped). */
fun withoutBody(template: String): String =
    template.lines().filterNot { it.trim() == BODY }.joinToString("\n").replace(BODY, "")

/**
 * How a snippet (a scratch stack or a template) is made parseable: statements go in a throwaway function
 * body, a value in a throwaway declaration. The prefix/suffix are fixed, so an edit computed against the
 * wrapped text maps straight back onto the snippet.
 */
class SnippetWrap(val kotlin: Boolean) {
    fun prefix(isValue: Boolean): String = when {
        kotlin && isValue -> "fun __scratch() {\nval __value = "
        kotlin -> "fun __scratch() {\n"
        isValue -> "class __Scratch {\nvoid __scratch() {\nObject __value = "
        else -> "class __Scratch {\nvoid __scratch() {\n"
    }

    fun suffix(isValue: Boolean): String = when {
        kotlin -> "\n}\n"
        isValue -> ";\n}\n}\n"
        else -> "\n}\n}\n"
    }

    fun wrap(text: String, isValue: Boolean): String = prefix(isValue) + text + suffix(isValue)

    /** The snippet back out of [wrapped], or null if an edit reached into the wrapper. */
    fun unwrap(wrapped: String, isValue: Boolean): String? {
        val p = prefix(isValue)
        val s = suffix(isValue)
        if (!wrapped.startsWith(p) || !wrapped.endsWith(s) || wrapped.length < p.length + s.length) return null
        return wrapped.substring(p.length, wrapped.length - s.length)
    }
}

/** Apply [edits] to [text] in descending offset order, or null when two of them overlap. */
fun applyTextEdits(text: String, edits: List<UiTextEdit>): String? {
    val sorted = edits.sortedByDescending { it.start }
    for (i in 1 until sorted.size) if (sorted[i].end > sorted[i - 1].start) return null
    val sb = StringBuilder(text)
    for (e in sorted) {
        if (e.start < 0 || e.end > sb.length || e.start > e.end) return null
        sb.setRange(e.start, e.end, e.newText)
    }
    return sb.toString()
}

/** [code]'s continuation lines with [indent] removed: a run lifted out of a body, re-based to column 0. */
fun dedent(code: String, indent: String): String =
    code.lines().mapIndexed { i, l -> if (i > 0 && l.startsWith(indent)) l.substring(indent.length) else if (i > 0) l.trimStart() else l }.joinToString("\n")

/** The leading whitespace of the line holding [offset]. */
fun indentAt(text: String, offset: Int): String {
    var ls = offset.coerceIn(0, text.length)
    while (ls > 0 && text[ls - 1] != '\n') ls--
    var i = ls
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return text.substring(ls, i)
}

// ---------------------------------------------------------------------------
// Scratch stacks: loose blocks kept beside the file, per function page.
// ---------------------------------------------------------------------------

/** A loose stack on a function page: its snippet [text], where it sits, and whether it is a lone value. */
data class ScratchStack(val key: Long, val page: String, val x: Float, val y: Float, val isValue: Boolean, val text: String)

/**
 * The sidecar format: a header line, then per stack a line `stack <x> <y> <value> <pageLength> <textLength>`
 * followed by exactly that many characters of page key and text. Length-prefixed, so a snippet may contain
 * anything.
 */
object ScratchCodec {
    private const val HEADER = "codeassist-block-stacks 1"

    fun encode(stacks: List<ScratchStack>): String? {
        if (stacks.isEmpty()) return null
        return buildString {
            append(HEADER).append('\n')
            for (s in stacks) {
                append("stack ").append(s.x).append(' ').append(s.y).append(' ').append(if (s.isValue) 1 else 0)
                    .append(' ').append(s.page.length).append(' ').append(s.text.length).append('\n')
                append(s.page).append(s.text).append('\n')
            }
        }
    }

    fun decode(data: String?): List<ScratchStack> {
        if (data == null || !data.startsWith(HEADER)) return emptyList()
        val out = ArrayList<ScratchStack>()
        var pos = HEADER.length + 1
        var key = 1L
        while (pos < data.length) {
            val eol = data.indexOf('\n', pos).takeIf { it >= 0 } ?: break
            val f = data.substring(pos, eol).split(' ')
            if (f.size != 6 || f[0] != "stack") break
            val pageLen = f[4].toIntOrNull() ?: break
            val textLen = f[5].toIntOrNull() ?: break
            val start = eol + 1
            if (start + pageLen + textLen > data.length) break
            out += ScratchStack(
                key++, data.substring(start, start + pageLen),
                f[1].toFloatOrNull() ?: 0f, f[2].toFloatOrNull() ?: 0f, f[3] == "1",
                data.substring(start + pageLen, start + pageLen + textLen),
            )
            pos = start + pageLen + textLen + 1
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// The palette's templates.
// ---------------------------------------------------------------------------

/** [Scope] is contextual: the functions the lambda body in focus can call (shown only while there is one). */
enum class PaletteCategory(val cat: BlockCat) {
    Scope(BlockCat.Method), Control(BlockCat.Control), Logic(BlockCat.Op), Math(BlockCat.Op), Text(BlockCat.Data),
    Variables(BlockCat.Data), Calls(BlockCat.Call), Compose(BlockCat.Compose),
}

/** One palette entry: [text] to insert (C-blocks carry the [BODY] marker), and whether it is a value. */
data class PaletteTemplate(val text: String, val isValue: Boolean, val imports: List<String> = emptyList())

fun paletteTemplates(category: PaletteCategory, kotlin: Boolean): List<PaletteTemplate> {
    fun s(t: String) = PaletteTemplate(t, false)
    fun v(t: String) = PaletteTemplate(t, true)
    return if (kotlin) when (category) {
        PaletteCategory.Control -> listOf(
            s("if (true) {\n$BODY\n}"), s("if (true) {\n$BODY\n} else {\n}"), s("for (item in items) {\n$BODY\n}"),
            s("for (i in 0 until 10) {\n$BODY\n}"), s("repeat(3) {\n$BODY\n}"), s("while (true) {\n$BODY\n}"),
            s("when (value) {\n    else -> {}\n}"), s("try {\n$BODY\n} catch (e: Exception) {\n}"),
            s("return"), s("break"), s("continue"),
        )
        PaletteCategory.Logic -> listOf(
            v("true"), v("false"), v("a == b"), v("a != b"), v("a < b"), v("a > b"), v("a && b"), v("a || b"),
            v("!a"), v("x == null"), v("if (a) b else c"),
        )
        PaletteCategory.Math -> listOf(v("0"), v("a + b"), v("a - b"), v("a * b"), v("a / b"), v("a % b"), v("(0..10).random()"), v("maxOf(a, b)"))
        PaletteCategory.Text -> listOf(
            v("\"text\""), v("\"\$a\""), v("a + b"), v("text.length"), v("text.isEmpty()"), v("text.uppercase()"),
            v("text.contains(\"x\")"), v("value.toString()"),
        )
        PaletteCategory.Calls -> listOf(s("println(\"\")"), s("Log.d(\"TAG\", \"message\")"), s("doSomething()"), v("list.size"), s("list.add(item)"), s("list.forEach { item ->\n$BODY\n}"))
        PaletteCategory.Compose -> listOf(
            s("Column {\n$BODY\n}"), s("Row {\n$BODY\n}"), s("Box {\n$BODY\n}"), s("Text(\"Hello\")"),
            s("Button(onClick = {}) {\n$BODY\n}"), s("Spacer(Modifier.height(8.dp))"),
            s("var count by remember { mutableStateOf(0) }"),
        )
        PaletteCategory.Variables, PaletteCategory.Scope -> emptyList()
    } else when (category) {
        PaletteCategory.Control -> listOf(
            s("if (true) {\n$BODY\n}"), s("if (true) {\n$BODY\n} else {\n}"), s("for (var item : items) {\n$BODY\n}"),
            s("for (int i = 0; i < 10; i++) {\n$BODY\n}"), s("while (true) {\n$BODY\n}"), s("do {\n$BODY\n} while (true);"),
            s("switch (value) {\n    default:\n        break;\n}"), s("try {\n$BODY\n} catch (Exception e) {\n}"),
            s("return;"), s("break;"), s("continue;"),
        )
        PaletteCategory.Logic -> listOf(v("true"), v("false"), v("a == b"), v("a != b"), v("a < b"), v("a > b"), v("a && b"), v("a || b"), v("!a"), v("x == null"))
        PaletteCategory.Math -> listOf(v("0"), v("a + b"), v("a - b"), v("a * b"), v("a / b"), v("a % b"), v("Math.max(a, b)"), v("(int) (Math.random() * 10)"))
        PaletteCategory.Text -> listOf(v("\"text\""), v("a + b"), v("text.length()"), v("text.isEmpty()"), v("text.toUpperCase()"), v("String.valueOf(value)"))
        PaletteCategory.Calls -> listOf(s("System.out.println(\"\");"), s("Log.d(\"TAG\", \"message\");"), s("doSomething();"), v("list.size()"), s("list.add(item);"))
        PaletteCategory.Compose, PaletteCategory.Variables, PaletteCategory.Scope -> emptyList()
    }
}

/**
 * The palette's Variables category: declarations to drag into a body, then for each name in scope a getter
 * value and a `set` statement.
 */
fun variableTemplates(names: List<String>, kotlin: Boolean): List<PaletteTemplate> {
    val declare = if (kotlin) listOf(PaletteTemplate("val name = 0", false), PaletteTemplate("var name = 0", false), PaletteTemplate("val name = \"\"", false))
    else listOf(PaletteTemplate("int name = 0;", false), PaletteTemplate("String name = \"\";", false), PaletteTemplate("var name = value;", false))
    return declare + names.distinct().flatMap { n ->
        listOf(PaletteTemplate(n, true), PaletteTemplate(if (kotlin) "$n = $n" else "$n = $n;", false))
    }
}

/** The place key the variable form uses for "a local of the open function". */
const val LOCAL_PLACE = "\u0000local"

// ---------------------------------------------------------------------------
// Declarations written by the forms.
// ---------------------------------------------------------------------------

/** What the variable form edits. [keyword] is Kotlin's `val`/`var`; Java leaves it empty. */
data class VariableSpec(val modifiers: String, val keyword: String, val name: String, val type: String, val initializer: String)

fun variableCode(v: VariableSpec, kotlin: Boolean): String {
    val mods = v.modifiers.trim().let { if (it.isEmpty()) "" else "$it " }
    return if (kotlin) buildString {
        append(mods).append(v.keyword.ifBlank { "val" }).append(' ').append(v.name.trim())
        if (v.type.isNotBlank()) append(": ").append(v.type.trim())
        if (v.initializer.isNotBlank()) append(" = ").append(v.initializer.trim())
    } else buildString {
        append(mods).append(v.type.trim().ifEmpty { "int" }).append(' ').append(v.name.trim())
        if (v.initializer.isNotBlank()) append(" = ").append(v.initializer.trim())
        append(';')
    }
}

data class ParamSpec(val name: String, val type: String)

/** What the function form edits. [annotations] go on their own line (`@Composable`, `@Override`). */
data class FunctionSpec(val annotations: String, val modifiers: String, val name: String, val params: List<ParamSpec>, val returnType: String)

/** The header [f] writes (no body), e.g. `private fun load(id: Int): String` or `public void run()`. */
fun functionHeader(f: FunctionSpec, kotlin: Boolean): String = buildString {
    if (f.annotations.isNotBlank()) append(f.annotations.trim()).append('\n')
    val mods = f.modifiers.trim().let { if (it.isEmpty()) "" else "$it " }
    val params = f.params.filter { it.name.isNotBlank() }
    if (kotlin) {
        append(mods).append("fun ").append(f.name.trim()).append('(')
        append(params.joinToString(", ") { "${it.name.trim()}: ${it.type.trim().ifEmpty { "Any" }}" })
        append(')')
        if (f.returnType.isNotBlank() && f.returnType.trim() != "Unit") append(": ").append(f.returnType.trim())
    } else {
        append(mods).append(f.returnType.trim().ifEmpty { "void" }).append(' ').append(f.name.trim()).append('(')
        append(params.joinToString(", ") { "${it.type.trim().ifEmpty { "Object" }} ${it.name.trim()}" })
        append(')')
    }
}

/** A whole new function: the header and an empty body. */
fun functionCode(f: FunctionSpec, kotlin: Boolean): String = functionHeader(f, kotlin) + " {\n}"

/** Parse a header back into a [FunctionSpec] for the edit form; null when it is not one the form can rewrite. */
fun parseFunctionHeader(signature: String, kotlin: Boolean): FunctionSpec? {
    val open = signature.indexOf('(')
    val close = signature.lastIndexOf(')')
    if (open < 0 || close < open) return null
    val before = signature.substring(0, open).trim()
    val params = splitTopLevel(signature.substring(open + 1, close)).map { it.trim() }.filter { it.isNotEmpty() }
    val after = signature.substring(close + 1).trim()
    val annotations = Regex("@[\\w.]+(\\([^)]*\\))?").findAll(before).joinToString(" ") { it.value }
    val bare = before.replace(Regex("@[\\w.]+(\\([^)]*\\))?"), "").trim()
    return if (kotlin) {
        val fnAt = Regex("\\bfun\\b").find(bare) ?: return null
        val modifiers = bare.substring(0, fnAt.range.first).trim()
        val name = bare.substring(fnAt.range.last + 1).trim().replace(Regex("^<[^>]*>\\s*"), "")
        if (name.contains('.')) return null // an extension function's receiver needs the code view
        val ps = params.map { p -> ParamSpec(p.substringBefore(':').trim(), p.substringAfter(':', "").trim()) }
        FunctionSpec(annotations, modifiers, name, ps, after.removePrefix(":").trim())
    } else {
        val words = bare.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val name = words.last()
        val returnType = words[words.size - 2]
        val modifiers = words.dropLast(2).joinToString(" ")
        if (after.isNotEmpty() && !after.startsWith("throws")) return null
        val ps = params.map { p ->
            val t = p.replace(Regex("@\\w+\\s*"), "").removePrefix("final ").trim()
            ParamSpec(t.substringAfterLast(' '), t.substringBeforeLast(' ', ""))
        }
        FunctionSpec(annotations, modifiers, name, ps, returnType)
    }
}

// ---------------------------------------------------------------------------
// Imports for code the block view writes.
// ---------------------------------------------------------------------------

/**
 * Where the Compose names the palette, the modifier editor and the forms write come from, so inserting
 * them also imports them: modifier extensions (`alpha` is `androidx.compose.ui.draw.alpha`), layouts, state
 * helpers, units and the common value types.
 */
val COMPOSE_IMPORTS: Map<String, String> = buildMap {
    for (n in listOf("padding", "offset", "fillMaxWidth", "fillMaxHeight", "fillMaxSize", "width", "height", "size", "wrapContentSize",
        "wrapContentWidth", "wrapContentHeight", "requiredSize", "aspectRatio", "Column", "Row", "Box", "Spacer", "Arrangement", "PaddingValues")) {
        put(n, "androidx.compose.foundation.layout.$n")
    }
    for (n in listOf("background", "border", "clickable", "verticalScroll", "horizontalScroll", "rememberScrollState", "Image")) put(n, "androidx.compose.foundation.$n")
    for (n in listOf("alpha", "clip", "shadow", "rotate", "scale", "drawBehind")) put(n, "androidx.compose.ui.draw.$n")
    for (n in listOf("RoundedCornerShape", "CircleShape", "CutCornerShape")) put(n, "androidx.compose.foundation.shape.$n")
    for (n in listOf("Text", "Button", "Card", "Surface", "Icon", "IconButton", "MaterialTheme", "Scaffold", "TextField", "OutlinedTextField")) put(n, "androidx.compose.material3.$n")
    for (n in listOf("Composable", "remember", "mutableStateOf", "getValue", "setValue", "LaunchedEffect")) put(n, "androidx.compose.runtime.$n")
    put("Modifier", "androidx.compose.ui.Modifier")
    put("Alignment", "androidx.compose.ui.Alignment")
    put("Color", "androidx.compose.ui.graphics.Color")
    put("dp", "androidx.compose.ui.unit.dp")
    put("sp", "androidx.compose.ui.unit.sp")
    put("FontWeight", "androidx.compose.ui.text.font.FontWeight")
    put("TextAlign", "androidx.compose.ui.text.style.TextAlign")
    put("Log", "android.util.Log")
}

/**
 * The imports [code] needs that [source] does not have yet: each known name it uses (not inside a string, not
 * as a named argument's name) whose import line, or its package's `*` import, is missing. A `var x by …`
 * delegate needs `getValue`/`setValue` too.
 */
fun missingImports(code: String, source: String): List<String> {
    val bare = code.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
    val used = LinkedHashSet<String>()
    Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\b(?!\\s*=(?!=))").findAll(bare).forEach { used += it.groupValues[1] }
    if (Regex("\\bvar\\s+\\w+\\s+by\\b").containsMatchIn(bare)) { used += "getValue"; used += "setValue" }
    if (Regex("\\bval\\s+\\w+\\s+by\\b").containsMatchIn(bare)) used += "getValue"
    val declared = Regex("^\\s*import\\s+([\\w.]*\\w(?:\\.\\*)?)", RegexOption.MULTILINE).findAll(source).map { it.groupValues[1] }.toSet()
    return used.mapNotNull { COMPOSE_IMPORTS[it] }.distinct().filter { fqn ->
        fqn !in declared && "${fqn.substringBeforeLast('.')}.*" !in declared
    }
}

/**
 * Edits that add `import` lines for [fqns] to a Kotlin [source], each at its sorted place among the existing
 * imports (after the `package` line when there are none).
 */
fun importEdits(source: String, fqns: List<String>): List<UiTextEdit> {
    if (fqns.isEmpty()) return emptyList()
    val lines = Regex("^import\\s+([\\w.*]+).*$", RegexOption.MULTILINE).findAll(source).map { it.groupValues[1] to it.range }.toList()
    if (lines.isEmpty()) {
        val pkg = Regex("^package\\s+[\\w.]+.*$", RegexOption.MULTILINE).find(source)
        val at = pkg?.range?.last?.plus(1) ?: 0
        val block = fqns.sorted().joinToString("") { "import $it\n" }
        return listOf(UiTextEdit(at, at, if (pkg != null) "\n\n" + block.trimEnd('\n') else block + "\n"))
    }
    // Group the new lines by where they go, so two imports landing at one offset are one edit in order.
    val byOffset = LinkedHashMap<Int, MutableList<String>>()
    for (fqn in fqns.sorted()) {
        val next = lines.firstOrNull { it.first > fqn }
        val at = next?.second?.first ?: (lines.last().second.last + 1)
        byOffset.getOrPut(at) { ArrayList() } += fqn
    }
    return byOffset.entries.sortedBy { it.key }.map { (at, list) ->
        val after = lines.none { it.second.first == at }
        val text = if (after) list.joinToString("") { "\nimport $it" } else list.joinToString("") { "import $it\n" }
        UiTextEdit(at, at, text)
    }
}

/**
 * A call template for a completion item `name(a: A, b: B)`: a trailing function-typed parameter becomes a
 * body (`name() {` + [BODY] + `}`, or `name {` + [BODY] + `}` when it is the only parameter), the rest an empty
 * argument list whose required parameters the block then shows as holes.
 */
fun callTemplate(label: String): String {
    val name = label.substringBefore('(').trim()
    val params = splitTopLevel(label.substringAfter('(', "").substringBeforeLast(')', "")).map { it.trim() }.filter { it.isNotEmpty() }
    val lambda = params.lastOrNull()?.let { "->" in it } == true
    return when {
        lambda && params.size == 1 -> "$name {\n$BODY\n}"
        lambda -> "$name() {\n$BODY\n}"
        else -> "$name()"
    }
}
