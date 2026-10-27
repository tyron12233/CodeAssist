package dev.ide.android.support

/**
 * Rewrites a layout's XML for the real-view preview the way Android Studio's preview reads it, before aapt2
 * compiles it (aapt2 drops the `tools` namespace and rejects data-binding expressions). Preview-only: it runs on
 * the preview's own copies, never on what the build compiles.
 *
 * - **Design-time attributes.** `tools:text="Jane"` stands in for `android:text` (overriding one that is set), for
 *   every attribute the framework declares, plus the RecyclerView attributes `tools:layoutManager`/`spanCount`/
 *   `reverseLayout`/`stackFromEnd`. A `@tools:sample/…` value becomes a fixed sample (a name, a city, lorem ipsum;
 *   a framework drawable for images).
 * - **List content.** `tools:listitem` (+ `tools:itemCount`) on a list is carried to the runtime in the view's
 *   `android:tag` as [LIST_TAG_PREFIX]`listitem=<ref>;itemCount=<n>[;tag=<original tag>]`, where the renderer fills
 *   the list with that item layout and restores the original tag.
 * - **Data binding.** A `<layout>` root is unwrapped to its view root (taking over its namespace declarations),
 *   `<data>` is dropped, and a `@{…}`/`@={…}` binding keeps its `default=` value or is removed.
 *
 * Every edit stays on the line it started on (removed text becomes spaces), so an aapt2 error in the rewritten
 * file still points at the user's line.
 */
object PreviewLayoutRewriter {

    /** Bumped whenever the output changes, so caches keyed on rewritten files are rebuilt. */
    const val VERSION = "1"

    const val LIST_TAG_PREFIX = "codeassist.tools:"

    private const val TOOLS_NS = "http://schemas.android.com/tools"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APP_NS = "http://schemas.android.com/apk/res-auto"

    /** `tools:` attributes that are instructions to the tools, not stand-ins for a real attribute. */
    private val NOT_SHADOWS = setOf(
        "context", "ignore", "targetApi", "showIn", "layout", "locale", "menu", "openDrawer", "parentTag",
        "viewBindingIgnore", "viewBindingType", "actionBarNavMode", "listheader", "listfooter", "keep", "discard",
        "shrinkMode", "node", "overrideLibrary", "replace", "remove", "selector", "strict", "useSampleData",
        "listitem", "itemCount", "sample",
    )

    /** RecyclerView's own (res-auto) attributes that `tools:` commonly shadows. */
    private val APP_SHADOWS = setOf("layoutManager", "spanCount", "reverseLayout", "stackFromEnd")

    private val TEXT_SAMPLES = mapOf(
        "full_names" to "Jane Doe",
        "first_names" to "Jane",
        "last_names" to "Doe",
        "cities" to "San Francisco",
        "us_zipcodes" to "94103",
        "us_phones" to "(415) 555-0100",
        "lorem" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
        "lorem/random" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.",
        "date/day_of_week" to "Monday",
        "date/ddmmyy" to "27-10-26",
        "date/mmddyy" to "10-27-26",
        "date/hhmm" to "12:30",
        "date/mmss" to "04:15",
    )

    private val IMAGE_SAMPLES = mapOf(
        "avatars" to "@android:drawable/sym_def_app_icon",
        "backgrounds/scenic" to "@android:color/darker_gray",
    )

    /**
     * [text] rewritten for the preview. [androidAttrs] are the framework's attribute names (`android.R.attr`), the
     * set a `tools:` attribute may stand in for; anything else is left for aapt2 to drop.
     */
    fun rewrite(text: String, androidAttrs: Set<String>): String {
        val tags = scan(text)
        val starts = tags.filterIsInstance<Tag.Start>()
        if (starts.isEmpty()) return text
        val prefixOf = HashMap<String, String>()   // namespace uri -> prefix
        for (t in starts) for (a in t.attrs) if (a.prefix == "xmlns") prefixOf.putIfAbsent(a.value(text), a.local)
        val tools = prefixOf[TOOLS_NS]
        val edits = ArrayList<Edit>()

        var viewRoot: Tag.Start? = starts.first()
        if (viewRoot?.name == "layout") viewRoot = unwrapDataBinding(text, tags, viewRoot, edits)
        // Design-time values first: one that overrides a bound attribute claims it from the binding pass.
        val claimed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Attr, Boolean>())
        if (tools != null) for (t in starts) designTimeAttributes(text, t, tools, prefixOf, androidAttrs, claimed, edits)
        dropBindingExpressions(text, starts, claimed, edits)
        return apply(text, edits)
    }

    // ---- data binding ------------------------------------------------------------------------------------

    /** Blanks the `<layout>` wrapper and its `<data>` block; moves the wrapper's xmlns declarations onto the
     *  view root. Returns the view root. */
    private fun unwrapDataBinding(text: String, tags: List<Tag>, layout: Tag.Start, edits: MutableList<Edit>): Tag.Start? {
        edits += Edit.blank(layout.start, layout.end)
        val depthOf = depths(tags)
        var viewRoot: Tag.Start? = null
        var i = tags.indexOf(layout) + 1
        while (i < tags.size) {
            val t = tags[i]
            if (t is Tag.Start && depthOf[i] == 1) {
                if (t.name == "data") {
                    val close = if (t.selfClosing) t else matchingEnd(tags, i)
                    edits += Edit.blank(t.start, close?.end ?: t.end)
                } else if (viewRoot == null) viewRoot = t
            }
            if (t is Tag.End && depthOf[i] == 0 && t.name == "layout") edits += Edit.blank(t.start, t.end)
            i++
        }
        val root = viewRoot ?: return null
        val xmlns = layout.attrs.filter { it.prefix == "xmlns" || it.qname == "xmlns" }
        val existing = root.attrs.map { it.qname }.toSet()
        val moved = xmlns.filter { it.qname !in existing }.joinToString("") { " " + text.substring(it.start, it.end) }
        if (moved.isNotEmpty()) edits += Edit(root.nameEnd, root.nameEnd, moved)
        return root
    }

    /** `@{expr, default=x}` keeps `x`; a binding with no default is removed. */
    private fun dropBindingExpressions(text: String, starts: List<Tag.Start>, claimed: Set<Attr>, edits: MutableList<Edit>) {
        for (t in starts) for (a in t.attrs) {
            if (a in claimed) continue
            val v = a.value(text).trim()
            if (!v.startsWith("@{") && !v.startsWith("@={")) continue
            val default = Regex("""\bdefault\s*=\s*([^,}]*)""").find(v)?.groupValues?.get(1)?.trim()
                ?.trim('`', '\'', '"')
            edits += if (default != null) Edit(a.valueStart, a.valueEnd, default) else Edit.blank(a.start, a.end)
        }
    }

    // ---- tools: ------------------------------------------------------------------------------------------

    private fun designTimeAttributes(
        text: String, t: Tag.Start, tools: String, prefixOf: Map<String, String>, androidAttrs: Set<String>,
        claimed: MutableSet<Attr>, edits: MutableList<Edit>,
    ) {
        var listItem: String? = null
        var itemCount: String? = null
        val inserts = StringBuilder()
        val declared = HashSet<String>()   // namespaces this element had to declare inline
        for (a in t.attrs) {
            if (a.prefix != tools) continue
            val value = a.value(text)
            when (a.local) {
                "listitem" -> { listItem = value; continue }
                "itemCount" -> { itemCount = value; continue }
            }
            if (a.local in NOT_SHADOWS) continue
            val ns = when (a.local) {
                in androidAttrs -> ANDROID_NS
                in APP_SHADOWS -> APP_NS
                else -> continue
            }
            val shadow = if (value.startsWith("@tools:sample/")) sample(a.local, value.removePrefix("@tools:sample/")) ?: continue
            else value
            val prefix = prefixOf[ns]
            val target = t.attrs.firstOrNull { it.prefix == prefix && prefix != null && it.local == a.local }
            if (target != null) { edits += Edit(target.valueStart, target.valueEnd, shadow); claimed += target }
            else inserts.append(' ').append(qualified(ns, prefix, a.local, declared)).append("=\"").append(shadow).append('"')
        }
        val item = listItem
        if (item != null) {
            val android = prefixOf[ANDROID_NS]
            val tag = t.attrs.firstOrNull { it.prefix == android && android != null && it.local == "tag" }
            val encoded = LIST_TAG_PREFIX + "listitem=$item;itemCount=${itemCount ?: ""}" +
                (tag?.let { ";tag=" + it.value(text) } ?: "")
            if (tag != null) { edits += Edit(tag.valueStart, tag.valueEnd, encoded); claimed += tag }
            else inserts.append(' ').append(qualified(ANDROID_NS, android, "tag", declared)).append("=\"").append(encoded).append('"')
        }
        if (inserts.isNotEmpty()) edits += Edit(t.attrsEnd, t.attrsEnd, inserts.toString())
    }

    /** `prefix:local`, declaring the namespace inline (once per element, in [declared]) when the file never did. */
    private fun qualified(ns: String, prefix: String?, local: String, declared: MutableSet<String>): String {
        if (prefix != null) return "$prefix:$local"
        val inline = if (ns == ANDROID_NS) "preview_android" else "preview_app"
        return if (declared.add(ns)) "xmlns:$inline=\"$ns\" $inline:$local" else "$inline:$local"
    }

    private fun sample(attr: String, name: String): String? {
        IMAGE_SAMPLES[name]?.let { return if (attr == "src" || attr == "background" || attr == "foreground") it else null }
        return TEXT_SAMPLES[name]?.takeIf { attr == "text" || attr == "hint" || attr == "contentDescription" }
    }

    // ---- scanning ----------------------------------------------------------------------------------------

    private class Attr(val qname: String, val start: Int, val end: Int, val valueStart: Int, val valueEnd: Int) {
        val prefix: String? get() = qname.substringBefore(':', "").ifEmpty { null }
        val local: String get() = qname.substringAfter(':')
        fun value(text: String) = text.substring(valueStart, valueEnd)
    }

    private sealed class Tag(val name: String, val start: Int, val end: Int) {
        class Start(
            name: String, start: Int, end: Int, val nameEnd: Int, val attrsEnd: Int,
            val attrs: List<Attr>, val selfClosing: Boolean,
        ) : Tag(name, start, end)
        class End(name: String, start: Int, end: Int) : Tag(name, start, end)
    }

    /** Start and end tags in document order, skipping comments, CDATA, processing instructions and DOCTYPE.
     *  Tolerant: a malformed tag ends the scan (aapt2 reports it on the untouched text). */
    private fun scan(text: String): List<Tag> {
        val out = ArrayList<Tag>()
        var i = 0
        val n = text.length
        while (true) {
            i = text.indexOf('<', i)
            if (i < 0 || i + 1 >= n) break
            when {
                text.startsWith("<!--", i) -> { i = text.indexOf("-->", i).let { if (it < 0) return out else it + 3 } }
                text.startsWith("<![CDATA[", i) -> { i = text.indexOf("]]>", i).let { if (it < 0) return out else it + 3 } }
                text[i + 1] == '?' || text[i + 1] == '!' -> { i = text.indexOf('>', i).let { if (it < 0) return out else it + 1 } }
                text[i + 1] == '/' -> {
                    val close = text.indexOf('>', i)
                    if (close < 0) return out
                    out += Tag.End(text.substring(i + 2, close).trim(), i, close + 1)
                    i = close + 1
                }
                else -> {
                    var j = i + 1
                    while (j < n && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
                    val name = text.substring(i + 1, j)
                    val nameEnd = j
                    val attrs = ArrayList<Attr>()
                    while (true) {
                        while (j < n && text[j].isWhitespace()) j++
                        if (j >= n) return out
                        if (text[j] == '>' || text.startsWith("/>", j)) break
                        val aStart = j
                        while (j < n && text[j] != '=' && !text[j].isWhitespace() && text[j] != '>') j++
                        val qname = text.substring(aStart, j)
                        while (j < n && text[j].isWhitespace()) j++
                        if (j >= n || text[j] != '=') return out
                        j++
                        while (j < n && text[j].isWhitespace()) j++
                        if (j >= n || (text[j] != '"' && text[j] != '\'')) return out
                        val quote = text[j]
                        val vEnd = text.indexOf(quote, j + 1)
                        if (vEnd < 0) return out
                        attrs += Attr(qname, aStart, vEnd + 1, j + 1, vEnd)
                        j = vEnd + 1
                    }
                    val selfClosing = text.startsWith("/>", j)
                    val end = if (selfClosing) j + 2 else j + 1
                    out += Tag.Start(name, i, end, nameEnd, j, attrs, selfClosing)
                    i = end
                }
            }
        }
        return out
    }

    /** Nesting depth of each tag (a start tag's depth is that of its element; an end tag's matches its start). */
    private fun depths(tags: List<Tag>): IntArray {
        val out = IntArray(tags.size)
        var depth = 0
        for ((i, t) in tags.withIndex()) when (t) {
            is Tag.Start -> { out[i] = depth; if (!t.selfClosing) depth++ }
            is Tag.End -> { depth--; out[i] = depth }
        }
        return out
    }

    private fun matchingEnd(tags: List<Tag>, startIndex: Int): Tag.End? {
        var depth = 0
        for (k in startIndex until tags.size) when (val t = tags[k]) {
            is Tag.Start -> if (!t.selfClosing) depth++
            is Tag.End -> { depth--; if (depth == 0) return t }
        }
        return null
    }

    // ---- edits -------------------------------------------------------------------------------------------

    private class Edit(val start: Int, val end: Int, val replacement: String) {
        companion object {
            /** Removes [start] until [end] but keeps its line breaks, so later lines keep their numbers. */
            fun blank(start: Int, end: Int) = Edit(start, end, "\u0000blank")
        }
    }

    private fun apply(text: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return text
        val sb = StringBuilder(text)
        // Later edits first so earlier offsets stay valid; an insert sorts after a replacement starting there.
        for (e in edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.end })) {
            val replacement = if (e.replacement == "\u0000blank")
                text.substring(e.start, e.end).map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("")
            else e.replacement
            sb.replace(e.start, e.end, replacement)
        }
        return sb.toString()
    }
}
