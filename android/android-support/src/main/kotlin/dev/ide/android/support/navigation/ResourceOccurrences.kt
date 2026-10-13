package dev.ide.android.support.navigation

import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceType

/**
 * One place a resource is named. [range] covers exactly the text a rename replaces, end-exclusive: the name
 * in `@string/app_name` and `R.string.app_name`, the whole class name for a [Kind.BINDING] use of a layout's
 * generated binding class (`ActivityMainBinding`), the field name for a [Kind.BINDING_FIELD]
 * (`binding.headerTitle`), the parent prefix for a style's [Kind.IMPLICIT_PARENT].
 */
data class ResourceOccurrence(val range: IntRange, val kind: Kind) {
    enum class Kind {
        /** The resource's own declaration: `name="…"` in `values`, or `@+id/…` on an `android:id`. */
        DECLARATION,
        /** A use: `@type/name`, `?attr/name`, `R.type.name`, `parent="…"`, a custom attribute name. */
        REFERENCE,
        /** A layout's generated ViewBinding / data-binding class. */
        BINDING,
        /** A view id's field on a binding class (`binding.headerTitle` for `@+id/header_title`). */
        BINDING_FIELD,
        /** `<style name="Parent.Child">` names `Parent` as its parent. */
        IMPLICIT_PARENT,
    }
}

/** An occurrence and the resource it names, by [key]: `"type/rName"` (see [ResourceOccurrences.key]), or
 *  `"binding/<Class>"` for a binding class, whose layout the scanner can't know from the class name alone. */
data class KeyedOccurrence(val key: String, val occurrence: ResourceOccurrence)

/**
 * Finds the resources a buffer names, by text. Pure and cheap (a few regex passes), so it serves both the
 * reference index (everything a file names, keyed: [allInXml] / [allInCode]) and the engine's per-resource
 * sweeps (one resource's ranges in one file: [inXml] / [inCode]) that Usages, rename and the layout ↔ class
 * "Related" links run on. The per-resource queries filter the keyed scan, so the two can never disagree.
 *
 * Names compare by their `R` field (`Theme.App` matches `R.style.Theme_App`), the way aapt2 sees them.
 */
object ResourceOccurrences {

    /** `R.type.name`, optionally qualified (`com.app.R.string.x`); `android.R` is the framework, not ours. */
    private val R_ACCESS = Regex("""(?<![\w$])((?:[A-Za-z_][\w]*\s*\.\s*)*)R\s*\.\s*([a-z]+)\s*\.\s*([A-Za-z_][\w]*)""")

    /** A generated binding class name (`ActivityMainBinding`). */
    private val BINDING_CLASS = Regex("""(?<![\w$])([A-Z][A-Za-z0-9]*Binding)(?![\w$])""")

    /** A `name="…"` attribute on a values element, capturing the element tag and the name. */
    private val VALUES_DECL = Regex("""<([A-Za-z][\w-]*)\b([^<>]*?)\bname\s*=\s*"([^"]*)"""")

    /** An attribute name with a namespace prefix: `app:cardElevation=`. */
    private val PREFIXED_ATTR = Regex("""(?<=\s)([A-Za-z_][\w.-]*):([A-Za-z_][\w]*)\s*=""")

    private val PARENT_ATTR = Regex("""\bparent\s*=\s*"([^"]*)"""")
    private val ITEM_NAME = Regex("""<item\b[^<>]*?\bname\s*=\s*"(?:([\w.]+):)?([^":]*)"""")
    private val TYPE_ATTR = Regex("""\btype\s*=\s*"([^"]*)"""")

    /** The key a resource is indexed and matched under: `"string/app_name"`, `"style/Theme_App"`. */
    fun key(type: ResourceType, name: String): String = "${type.rClass}/${sanitizeResourceName(name)}"

    /** The key a layout's binding class is indexed under. */
    fun bindingKey(bindingClass: String): String = "binding/$bindingClass"

    // ---- everything a buffer names ------------------------------------------------------------------------

    /** Every resource an XML buffer names. [isValuesFile] turns on the `name="…"` declaration forms. */
    fun allInXml(text: String, isValuesFile: Boolean): List<KeyedOccurrence> {
        val out = ArrayList<KeyedOccurrence>()
        fun add(type: ResourceType, name: String, range: IntRange, kind: ResourceOccurrence.Kind) {
            out += KeyedOccurrence(key(type, name), ResourceOccurrence(range, kind))
        }

        for (ref in ResourceReferences.scan(text)) {
            if (ref.packageName != null) continue // `@android:…` / `@pkg:…` name another package's resource
            val type = ref.type ?: continue
            if (ref.name in PSEUDO) continue
            val nameStart = ref.range.last + 1 - ref.name.length
            val kind = if (ref.create && isIdAttributeValue(text, ref.range.first)) ResourceOccurrence.Kind.DECLARATION
            else ResourceOccurrence.Kind.REFERENCE
            add(type, ref.name, nameStart until nameStart + ref.name.length, kind)
        }

        for (m in VALUES_DECL.findAll(text)) {
            if (m.groupValues[1] != "style") continue
            val styleName = m.groupValues[3]
            val nameStart = m.groups[3]!!.range.first
            // Implicit parent: every proper dotted prefix of the style's own name.
            var dot = styleName.lastIndexOf('.')
            while (dot > 0) {
                add(ResourceType.STYLE, styleName.substring(0, dot), nameStart until nameStart + dot, ResourceOccurrence.Kind.IMPLICIT_PARENT)
                dot = styleName.lastIndexOf('.', dot - 1)
            }
            // Explicit `parent="…"` (a raw name; `@style/…` was already found as a reference).
            val tagEnd = text.indexOf('>', m.range.last).let { if (it < 0) text.length else it }
            PARENT_ATTR.find(text.substring(m.range.first, tagEnd))?.let { p ->
                val value = p.groupValues[1]
                if (value.isEmpty() || value.startsWith("@") || value.startsWith("android:")) return@let
                val s = m.range.first + p.groups[1]!!.range.first
                add(ResourceType.STYLE, value, s until s + value.length, ResourceOccurrence.Kind.REFERENCE)
            }
        }

        // A custom attribute is used as an attribute name (`app:cardElevation="…"`) and as a style item.
        for (m in PREFIXED_ATTR.findAll(text)) {
            if (m.groupValues[1] in NON_ATTR_PREFIXES) continue
            val g = m.groups[2]!!.range
            add(ResourceType.ATTR, m.groupValues[2], g, ResourceOccurrence.Kind.REFERENCE)
        }
        for (m in ITEM_NAME.findAll(text)) {
            if (m.groupValues[1] == "android" || m.groupValues[2].isEmpty()) continue
            if (!isInsideStyle(text, m.range.first)) continue
            val g = m.groups[2]!!.range
            add(ResourceType.ATTR, m.groupValues[2], g, ResourceOccurrence.Kind.REFERENCE)
        }

        if (isValuesFile) {
            for (m in VALUES_DECL.findAll(text)) {
                val tag = m.groupValues[1]
                val raw = m.groupValues[3]
                if (raw.isEmpty() || ':' in raw) continue
                val type = when (tag) {
                    "item" -> {
                        if (isInsideStyle(text, m.range.first)) continue
                        val tagEnd = text.indexOf('>', m.range.first).let { if (it < 0) text.length else it }
                        TYPE_ATTR.find(text.substring(m.range.first, tagEnd))?.groupValues?.get(1)?.let { ResourceType.byRClass(it) }
                    }
                    else -> ResourceType.fromValueTag(tag)
                } ?: continue
                add(type, raw, m.groups[3]!!.range, ResourceOccurrence.Kind.DECLARATION)
            }
        }
        return out.distinctBy { it.key to it.occurrence.range.first }.sortedBy { it.occurrence.range.first }
    }

    /** Every resource a Java/Kotlin buffer names: `R.type.name`, and the binding classes it uses (keyed by
     *  class, see [bindingKey]). Binding FIELDS aren't included: which ids a field names depends on the
     *  layout, so they're found per resource by [inCode]. */
    fun allInCode(text: String): List<KeyedOccurrence> {
        val out = ArrayList<KeyedOccurrence>()
        for (m in R_ACCESS.findAll(text)) {
            if (isFrameworkQualifier(m.groupValues[1])) continue
            val type = ResourceType.byRClass(m.groupValues[2]) ?: continue
            val g = m.groups[3]!!.range
            out += KeyedOccurrence(key(type, m.groupValues[3]), ResourceOccurrence(g, ResourceOccurrence.Kind.REFERENCE))
        }
        for (m in BINDING_CLASS.findAll(text)) {
            val g = m.groups[1]!!.range
            out += KeyedOccurrence(bindingKey(m.groupValues[1]), ResourceOccurrence(g, ResourceOccurrence.Kind.BINDING))
        }
        return out.sortedBy { it.occurrence.range.first }
    }

    // ---- one resource -------------------------------------------------------------------------------------

    /** One resource's occurrences in an XML buffer. */
    fun inXml(text: String, type: ResourceType, name: String, isValuesFile: Boolean): List<ResourceOccurrence> {
        if (!text.contains(lastSegmentHint(name))) return emptyList()
        val k = key(type, name)
        return allInXml(text, isValuesFile).filter { it.key == k }.map { it.occurrence }
    }

    /**
     * One resource's occurrences in a Java/Kotlin buffer: `R.type.name`; for a layout, its binding class; for
     * an id, its field on [idBindings] (the binding classes of the layouts declaring it) when the buffer uses
     * one of them (`binding.headerTitle`, not a call).
     */
    fun inCode(
        text: String,
        type: ResourceType,
        name: String,
        idBindings: Collection<String> = emptyList(),
    ): List<ResourceOccurrence> {
        val rName = sanitizeResourceName(name)
        val k = key(type, name)
        val binding = if (type == ResourceType.LAYOUT) bindingKey(bindingClassName(rName)) else null
        val out = allInCode(text).filter { it.key == k || it.key == binding }.mapTo(ArrayList()) { it.occurrence }
        if (type == ResourceType.ID && idBindings.any { usesClass(text, it) }) {
            val field = bindingFieldName(rName)
            val access = Regex("""(?<![\w$])${Regex.escape(field)}(?![\w$])(?!\s*\()""")
            for (m in access.findAll(text)) {
                if (!followsDot(text, m.range.first)) continue
                out += ResourceOccurrence(m.range, ResourceOccurrence.Kind.BINDING_FIELD)
            }
        }
        return out.sortedBy { it.range.first }
    }

    /** Every resource `R.type.name` a code buffer names, as (type, name). For "Related" from a class. */
    fun referencedInCode(text: String): List<Pair<ResourceType, String>> =
        R_ACCESS.findAll(text).mapNotNull { m ->
            if (isFrameworkQualifier(m.groupValues[1])) return@mapNotNull null
            ResourceType.byRClass(m.groupValues[2])?.let { it to m.groupValues[3] }
        }.distinct().toList()

    /** The layouts a code buffer reaches through a generated binding class (`ActivityMainBinding` → activity_main).
     *  [layouts] are the candidate layout names; only those whose binding class appears are returned. */
    fun bindingLayoutsIn(text: String, layouts: Collection<String>): List<String> =
        layouts.filter { usesClass(text, bindingClassName(it)) }

    /** Every `R.type.name` access in a code buffer, in order, with the name's range (`android.R.…` marked as the
     *  framework's). Unknown `R` classes are skipped. */
    fun codeReferences(text: String): List<XmlNavTarget.Resource> =
        R_ACCESS.findAll(text).mapNotNull { m ->
            val type = ResourceType.byRClass(m.groupValues[2]) ?: return@mapNotNull null
            val g = m.groups[3]!!.range
            val pkg = if (isFrameworkQualifier(m.groupValues[1])) "android" else null
            XmlNavTarget.Resource(type, m.groupValues[3], pkg, false, g.first..g.last)
        }.toList()

    /** The `R.type.name` access under [offset] in a code buffer, as a [XmlNavTarget.Resource] whose range is the
     *  name (`android.R.…` comes back as a framework reference). Null when the caret isn't on one. */
    fun codeReferenceAt(text: String, offset: Int): XmlNavTarget.Resource? {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        if (lineStart > lineEnd) return null
        val line = text.substring(lineStart, lineEnd)
        for (m in R_ACCESS.findAll(line)) {
            val whole = m.range
            if (offset - lineStart < whole.first || offset - lineStart > whole.last + 1) continue
            val type = ResourceType.byRClass(m.groupValues[2]) ?: return null
            val g = m.groups[3]!!.range
            val pkg = if (isFrameworkQualifier(m.groupValues[1])) "android" else null
            return XmlNavTarget.Resource(type, m.groupValues[3], pkg, false, lineStart + g.first until lineStart + g.last + 1)
        }
        return null
    }

    /** The ViewBinding / data-binding class aapt generates for layout [layout]: `activity_main` → `ActivityMainBinding`. */
    fun bindingClassName(layout: String): String =
        layout.split('_', '-', '.').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } } + "Binding"

    /** The field a binding class has for view id [id]: `header_title` → `headerTitle`. */
    fun bindingFieldName(id: String): String {
        val parts = id.split('_', '-', '.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return id
        return parts.first().replaceFirstChar { it.lowercaseChar() } +
            parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun usesClass(text: String, cls: String): Boolean {
        var i = text.indexOf(cls)
        while (i >= 0) {
            val end = i + cls.length
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (end < text.length) text[end] else ' '
            if (!before.isJavaIdentifierPart() && !after.isJavaIdentifierPart()) return true
            i = text.indexOf(cls, end)
        }
        return false
    }

    /** Whether the identifier at [at] is a member access: preceded (past whitespace) by `.` or `?.`. */
    private fun followsDot(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && text[i] == '.'
    }

    private fun isFrameworkQualifier(qualifier: String): Boolean =
        qualifier.replace(Regex("""\s"""), "").removeSuffix(".") == "android"

    /** Whether the element starting at [at] sits inside an open `<style>` (a style `<item>`, not a value item). */
    private fun isInsideStyle(text: String, at: Int): Boolean {
        val open = text.lastIndexOf("<style", at)
        if (open < 0) return false
        val close = text.lastIndexOf("</style>", at)
        return close < open
    }

    /** Whether the `@+id/…` at [refStart] is the value of an `…:id` attribute (a declaration), not a constraint. */
    private fun isIdAttributeValue(text: String, refStart: Int): Boolean {
        var i = refStart - 1
        if (i < 0 || (text[i] != '"' && text[i] != '\'')) return false
        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != '=') return false
        i--
        while (i >= 0 && text[i].isWhitespace()) i--
        val end = i + 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == ':')) i--
        val attr = text.substring(i + 1, end)
        return attr == "id" || attr.endsWith(":id")
    }

    /** A cheap pre-filter: the name's last segment must appear for anything to match. Split on every separator
     *  a name may be written with, so `Theme_App` (from `R.style`) still finds `@style/Theme.App`. */
    private fun lastSegmentHint(name: String): String =
        name.split('.', '_', '-').lastOrNull { it.isNotEmpty() } ?: name

    private val PSEUDO = setOf("null", "empty", "undefined")
    private val NON_ATTR_PREFIXES = setOf("android", "tools", "xmlns")
}
