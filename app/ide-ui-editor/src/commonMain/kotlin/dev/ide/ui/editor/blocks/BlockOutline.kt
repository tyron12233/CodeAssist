package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart

/*
 * The block view's entry point, Sketchware-style: a file is not shown as one wall of blocks but as an outline
 * of what it declares, grouped by class or object, and each function opens on its own page. Variables
 * (top-level properties, fields, constants) are listed and edited through forms rather than drawn as blocks.
 *
 * Built from the projected block tree, so it needs nothing from the backend beyond the projection.
 */

enum class FunctionKind { Function, Constructor, Init, Accessor }

enum class GroupKind { TopLevel, Class, Interface, Object, Enum, Companion }

class OutlineFunction(
    val node: UiBlockNode,
    val kind: FunctionKind,
    val name: String,
    /** The header as written, whitespace-collapsed (`fun render(items: List<String>)`). */
    val signature: String,
    /** Identity that survives re-projection: the owning group plus the signature. */
    val key: String,
    /** Statements in its body, or -1 for an expression body. */
    val statements: Int,
    val group: String,
)

class OutlineVariable(
    val node: UiBlockNode,
    /** `val`/`var` for Kotlin (with `const` folded into [modifiers]); empty for Java. */
    val keyword: String,
    val name: String,
    val type: String?,
    val initializer: String?,
    val modifiers: String,
    /** False when the declaration does more than a form can rewrite (`int a, b;`, a delegate, accessors). */
    val editable: Boolean,
    val group: String,
)

class OutlineGroup(
    val key: String,
    val title: String,
    val kind: GroupKind,
    val depth: Int,
    /** The member list a new function or variable is inserted into, if there is one. */
    val body: BodyRef?,
    val functions: List<OutlineFunction>,
    val variables: List<OutlineVariable>,
    val node: UiBlockNode?,
)

class FileOutline(val groups: List<OutlineGroup>, val kotlin: Boolean) {
    val functions: List<OutlineFunction> get() = groups.flatMap { it.functions }
    val variables: List<OutlineVariable> get() = groups.flatMap { it.variables }

    /** The function with [key], or (after a signature edit renamed the key) the one called [name] in [group]. */
    fun find(key: String, name: String?, group: String?): OutlineFunction? =
        functions.firstOrNull { it.key == key }
            ?: functions.firstOrNull { name != null && it.name == name && it.group == group }
}

fun buildOutline(root: UiBlockNode, source: String, kotlin: Boolean): FileOutline {
    val groups = ArrayList<OutlineGroup>()
    val tops = bodyOf(root)
    if (kotlin) {
        val members = tops?.children ?: emptyList()
        groups += group("", "", GroupKind.TopLevel, 0, tops, members, source, kotlin, null)
        members.filter { it.label == "class" }.forEach { collectClass(it, "", 0, source, kotlin, groups) }
    } else {
        (tops?.children ?: emptyList()).filter { it.label == "class" }.forEach { collectClass(it, "", 0, source, kotlin, groups) }
    }
    return FileOutline(groups.filter { it.kind != GroupKind.TopLevel || it.functions.isNotEmpty() || it.variables.isNotEmpty() || groups.size == 1 }, kotlin)
}

private fun collectClass(cls: UiBlockNode, outer: String, depth: Int, source: String, kotlin: Boolean, out: MutableList<OutlineGroup>) {
    val (kind, name) = classIdentity(cls, source, kotlin)
    val title = when {
        kind == GroupKind.Companion -> listOf(outer, "companion object").filter { it.isNotEmpty() }.joinToString(".")
        else -> listOf(outer, name).filter { it.isNotEmpty() }.joinToString(".")
    }
    val body = bodyOf(cls)
    val members = body?.children ?: emptyList()
    out += group(title, title, kind, depth, body, members, source, kotlin, cls)
    members.filter { it.label == "class" }.forEach { collectClass(it, title, depth + 1, source, kotlin, out) }
}

private fun group(
    key: String, title: String, kind: GroupKind, depth: Int, body: BodyRef?, members: List<UiBlockNode>,
    source: String, kotlin: Boolean, node: UiBlockNode?,
): OutlineGroup {
    val functions = ArrayList<OutlineFunction>()
    val variables = ArrayList<OutlineVariable>()
    val className = if (kind == GroupKind.TopLevel) null else title.substringAfterLast('.')
    for (m in members) when (m.label) {
        "method" -> functions += function(m, if (!kotlin && declName(m, source) == className) FunctionKind.Constructor else FunctionKind.Function, key, source)
        "constructor" -> if (bodyOf(m) != null) functions += function(m, FunctionKind.Constructor, key, source)
        "init" -> functions += function(m, FunctionKind.Init, key, source)
        "field" -> {
            val v = variable(m, key, source, kotlin)
            variables += v
            accessorsOf(m).forEach { a -> functions += function(a, FunctionKind.Accessor, key, source, owner = v.name) }
        }
    }
    return OutlineGroup(key, title, kind, depth, body, functions, variables, node)
}

private fun function(node: UiBlockNode, kind: FunctionKind, group: String, source: String, owner: String? = null): OutlineFunction {
    val signature = signatureText(node, source)
    val name = when (kind) {
        FunctionKind.Init -> "init"
        FunctionKind.Accessor -> (if (signature.trimStart().startsWith("set")) "set" else "get") + " · " + (owner ?: "")
        FunctionKind.Constructor -> declName(node, source) ?: "constructor"
        FunctionKind.Function -> declName(node, source) ?: signature
    }
    val body = bodyOf(node)
    return OutlineFunction(node, kind, name, signature, "$group/${kind.name}:${owner.orEmpty()}:$signature", body?.children?.size ?: -1, group)
}

/** A declaration's own name: the Kotlin `declname` field, or Java's name token before the parameter list. */
fun declName(node: UiBlockNode, source: String): String? {
    node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.role == "declname" }?.text }?.let { return it }
    val header = signatureText(node, source)
    return Regex("([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(").find(header)?.groupValues?.get(1)
}

private fun classIdentity(cls: UiBlockNode, source: String, kotlin: Boolean): Pair<GroupKind, String> {
    val header = signatureText(cls, source)
    val kw = Regex("\\b(class|interface|object|enum|record)\\b").find(header)?.value ?: "class"
    val name = cls.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.role == "declname" }?.text }
        ?: cls.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s -> s.children.firstOrNull { it.kind == "name_ref" } }
            ?.let { source.substring(it.start, it.end) }
        ?: Regex("\\b(?:class|interface|object|enum|record)\\s+([A-Za-z_$][\\w$]*)").find(header)?.groupValues?.get(1)
        ?: ""
    val kind = when {
        kotlin && "companion" in header && kw == "object" -> GroupKind.Companion
        kw == "object" -> GroupKind.Object
        kw == "interface" -> GroupKind.Interface
        kw == "enum" || "enum class" in header -> GroupKind.Enum
        else -> GroupKind.Class
    }
    return kind to name
}

private fun accessorsOf(prop: UiBlockNode): List<UiBlockNode> =
    prop.parts.filterIsInstance<UiBlockPart.Slot>().mapNotNull { it.children.singleOrNull() }.filter { it.label == "accessor" }

private fun variable(node: UiBlockNode, group: String, source: String, kotlin: Boolean): OutlineVariable {
    fun text(n: UiBlockNode) = source.substring(n.start.coerceIn(0, source.length), n.end.coerceIn(0, source.length))
    val slots = node.parts.filterIsInstance<UiBlockPart.Slot>()
    val type = slots.firstOrNull { it.category == "TYPE" }?.children?.singleOrNull()?.let(::text)
    val init = initializerOf(node)?.let(::text)
    if (kotlin) {
        val kw = node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.role == "keyword" } }
        val name = declName(node, source) ?: ""
        val modifiers = kw?.let { source.substring(node.start, it.start).trim() } ?: ""
        val tail = source.substring(node.start, node.end)
        val editable = accessorsOf(node).isEmpty() && " by " !in tail && kw != null
        return OutlineVariable(node, kw?.text ?: "val", name, type, init, modifiers.replace(Regex("\\s+"), " "), editable, group)
    }
    val fragments = slots.mapNotNull { it.children.singleOrNull() }.filter { it.label == "var" || it.kind == "local_var" }
    val name = fragments.firstOrNull()?.let { f -> f.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "name_ref" } } }?.let(::text) ?: ""
    val typeSlot = slots.firstOrNull { it.category == "TYPE" }
    val modifiers = typeSlot?.let { source.substring(node.start, it.start).trim() } ?: ""
    return OutlineVariable(node, "", name, type, init, modifiers.replace(Regex("\\s+"), " "), fragments.size == 1, group)
}

/** The variables a function page can read and write, nearest first: its group's, then the top level's. */
fun variablesInScope(outline: FileOutline, fn: OutlineFunction?): List<OutlineVariable> {
    val own = outline.groups.firstOrNull { it.key == fn?.group }?.variables ?: emptyList()
    val top = if (fn?.group != "") outline.groups.firstOrNull { it.kind == GroupKind.TopLevel }?.variables ?: emptyList() else emptyList()
    return own + top
}

/** A function's parameter names, from its header (`(a: Int, b: String)` or `(int a, String b)`). */
fun parameterNames(fn: OutlineFunction, kotlin: Boolean): List<String> {
    val params = fn.signature.substringAfter('(', "").substringBeforeLast(')', "")
    if (params.isBlank()) return emptyList()
    return splitTopLevel(params).mapNotNull { p ->
        val t = p.trim().replace(Regex("@\\w+(\\([^)]*\\))?\\s*"), "").removePrefix("final ").removePrefix("vararg ").trim()
        if (kotlin) t.substringBefore(':').trim().removePrefix("val ").removePrefix("var ").trim().ifEmpty { null }
        else t.substringAfterLast(' ').removePrefix("...").trim().ifEmpty { null }
    }
}

/** Split [s] at commas that are not inside `<>`, `()` or `[]`. */
fun splitTopLevel(s: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    val cur = StringBuilder()
    for (c in s) {
        when (c) {
            '<', '(', '[' -> depth++
            '>', ')', ']' -> depth--
        }
        if (c == ',' && depth == 0) { out += cur.toString(); cur.clear() } else cur.append(c)
    }
    if (cur.isNotBlank()) out += cur.toString()
    return out
}

/** The names of the locals [fn] declares in its body (and nested bodies), in source order. */
fun localNames(fn: OutlineFunction, source: String): List<String> {
    val out = ArrayList<String>()
    fun visit(n: UiBlockNode) {
        if (n.label == "var" && n.kind == "local_var") {
            val name = n.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.role == "declname" }?.text }
                ?: n.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s ->
                    s.children.singleOrNull()?.let { c -> if (c.kind == "name_ref") c else c.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { cs -> cs.children.singleOrNull()?.takeIf { it.kind == "name_ref" } } }
                }?.let { source.substring(it.start, it.end) }
            if (name != null) out += name
        }
        for (p in n.parts) if (p is UiBlockPart.Slot) p.children.forEach(::visit)
    }
    bodyOf(fn.node)?.children?.forEach(::visit)
    return out
}
