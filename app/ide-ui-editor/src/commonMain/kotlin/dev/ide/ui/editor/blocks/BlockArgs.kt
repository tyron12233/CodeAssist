package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart

/*
 * What a call can take, as far as the block view is concerned: the callee's parameters (from the resolver's
 * signature help), which of them the call already supplies, and so where the missing ones are holes.
 */

/** One parameter: its name (null when only a type is known), its type, and whether it has a default. */
data class ParamSig(val name: String?, val type: String, val optional: Boolean, val vararg: Boolean)

/** A callee's overloads (each a parameter list) and the one shown. */
data class CallSig(val overloads: List<List<ParamSig>>, val chosen: Int) {
    val params: List<ParamSig> get() = overloads.getOrElse(chosen) { emptyList() }
}

/** The key a call segment's signature is stored under: the call's source start and the segment index. */
fun callKey(callStart: Int, link: Int) = "$callStart:$link"

/**
 * Read a parameter label as signature help renders it: Kotlin `name: Type` (`= …` when defaulted, `vararg `
 * in front), Java `Type name`.
 */
fun parseParamLabel(label: String, kotlin: Boolean): ParamSig {
    var t = label.trim()
    val optional = t.endsWith("= …") || t.endsWith("= ...")
    if (optional) t = t.substringBeforeLast('=').trim()
    val vararg = t.startsWith("vararg ") || t.endsWith("...")
    t = t.removePrefix("vararg ").trim()
    return if (kotlin || ':' in t) {
        val colon = t.indexOf(':')
        if (colon < 0) ParamSig(null, t, optional, vararg) else ParamSig(t.substring(0, colon).trim(), t.substring(colon + 1).trim(), optional, vararg)
    } else {
        val sp = t.lastIndexOf(' ')
        if (sp < 0) ParamSig(null, t.removeSuffix("..."), optional, vararg)
        else ParamSig(t.substring(sp + 1), t.substring(0, sp).removeSuffix("...").trim(), optional, vararg)
    }
}

/** The value shape a parameter type asks for. */
fun shapeOfType(type: String): ValueShape = when (type.trim().removeSuffix("?").substringBefore('<').trim()) {
    "Boolean", "boolean" -> ValueShape.Boolean
    "Int", "Long", "Short", "Byte", "Float", "Double", "int", "long", "short", "byte", "float", "double", "Dp", "TextUnit", "Number" -> ValueShape.Number
    "String", "CharSequence", "Char", "char", "AnnotatedString" -> ValueShape.Text
    else -> ValueShape.Unknown
}

/** What a call segment already supplies: its arguments in order, how many are positional, the names given. */
class Supplied(val written: List<UiBlockPart.Slot>, val positional: Int, val named: Set<String>, val trailingLambda: Boolean, val emptySlot: UiBlockPart.Slot?)

/** The parts of segment [link] of [call] (from its name to the next segment's), or null for a field hop. */
fun segmentParts(call: UiBlockNode, link: Int): List<UiBlockPart>? {
    val parts = call.parts
    val names = parts.indices.filter { val p = parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE_ARGS.matches(p.role) }
    val at = names.getOrNull(link) ?: return null
    val end = names.getOrNull(link + 1) ?: parts.size
    return parts.subList(at, end)
}

private val NAME_ROLE_ARGS = Regex("name\\d*")

fun supplied(segment: List<UiBlockPart>): Supplied {
    val written = ArrayList<UiBlockPart.Slot>()
    val named = LinkedHashSet<String>()
    var positional = 0
    var sawNamed = false
    var empty: UiBlockPart.Slot? = null
    var lambda = false
    segment.forEachIndexed { i, p ->
        val slot = p as? UiBlockPart.Slot ?: return@forEachIndexed
        when (slot.category) {
            "ARGUMENT" -> {
                if (slot.children.isEmpty()) { empty = slot; return@forEachIndexed }
                written += slot
                val before = (segment.getOrNull(i - 1) as? UiBlockPart.Field)?.text ?: ""
                val name = Regex("(\\w+)\\s*=\\s*$").find(before)?.groupValues?.get(1)
                if (name != null) { named += name; sawNamed = true } else if (!sawNamed) positional++
            }
            "STATEMENT" -> lambda = true
        }
    }
    return Supplied(written, positional, named, lambda, empty)
}

/** A missing parameter to show as a hole: which one, and how filling it is written. */
class MissingParam(val index: Int, val param: ParamSig, val named: Boolean)

/**
 * The parameters [sig] has that the call does not supply yet. Kotlin shows every missing required one (and
 * the optional ones when [expanded]), filled positionally when it is next in line and by name otherwise; Java
 * has only positions, so its next parameter is the one hole. [hiddenOptional] counts what stays folded.
 */
fun missingParams(sig: CallSig, s: Supplied, kotlin: Boolean, expanded: Boolean): Pair<List<MissingParam>, Int> {
    val params = sig.params
    // A trailing lambda is always the last parameter, whatever its type renders as.
    val lambdaIndex = if (s.trailingLambda) params.lastIndex else -1
    val out = ArrayList<MissingParam>()
    var hidden = 0
    for ((i, p) in params.withIndex()) {
        if (i < s.positional || (p.name != null && p.name in s.named) || i == lambdaIndex) continue
        if (p.vararg && s.positional > i) continue
        if (!kotlin) {
            if (i == s.written.size) out += MissingParam(i, p, named = false)
            continue
        }
        if (p.optional && !expanded) { hidden++; continue }
        val inLine = i == s.positional && s.named.isEmpty()
        out += MissingParam(i, p, named = !inLine && p.name != null)
    }
    // The empty `()` hole already stands for the first parameter.
    if (s.emptySlot != null && out.firstOrNull()?.index == 0 && !out.first().named) out.removeAt(0)
    return out to hidden
}

/** The receiver of a function type with one (`@Composable ColumnScope.() -> Unit` -> `ColumnScope`), or null. */
fun lambdaReceiver(type: String): String? {
    val t = type.trim().replace(Regex("^(@[\\w.]+\\s+)+"), "").removePrefix("suspend ").trim()
    val m = Regex("^([\\w.<>?, ]+?)\\.\\(").find(t) ?: return null
    return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
}
