package dev.ide.android.support.index

import dev.ide.android.support.resources.ResourceType
import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler
import java.io.DataInput
import java.io.DataOutput
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * A resource declaration found in a `res/` file: its type, name, declaring file, and offset (for go-to), plus
 * the resolved literal [value] for a value resource (a string's text, a color's `#…`, a dimen's `16dp`),
 * length-capped and null for file resources / ids. Carrying the value here lets resource-name completion show
 * the resolved-value hint from ONE index query, instead of re-parsing/fingerprinting the resource repository
 * per candidate per keystroke.
 */
data class ResourceDeclValue(
    val type: String, val name: String, val filePath: String, val offset: Int, val value: String? = null,
)

object ResourceDeclExternalizer : Externalizer<ResourceDeclValue> {
    override fun write(out: DataOutput, value: ResourceDeclValue) {
        out.writeUTF(value.type); out.writeUTF(value.name); out.writeUTF(value.filePath); out.writeInt(value.offset)
        out.writeBoolean(value.value != null)
        if (value.value != null) out.writeUTF(value.value)
    }
    override fun read(inp: DataInput): ResourceDeclValue {
        val type = inp.readUTF(); val name = inp.readUTF(); val path = inp.readUTF(); val offset = inp.readInt()
        val value = if (inp.readBoolean()) inp.readUTF() else null
        return ResourceDeclValue(type, name, path, offset, value)
    }
}

/**
 * Index key = `"<type>/<name>"` (e.g. `string/app_name`). Keying by `type/` lets completion enumerate one
 * resource type by prefix (`prefix("string/")`) and resolve a reference exactly (`exact("string/app_name")`),
 * while still supporting fuzzy matching on the whole key. Build a key with [key].
 */
private object ResourceKeyDescriptor : KeyDescriptor<String> {
    override fun asTerm(key: String): String = key
    override fun fromTerm(term: String): String = term
    override fun compare(a: String, b: String): Int = a.compareTo(b)
}

/**
 * The incremental Android resource-declaration index. Indexes `res/` XML files (value resources,
 * file resources, and `@+id/` declarations) keyed by resource name, with the declaring file + offset as the
 * value, so resource references resolve to a precise definition and resource-name completion is fast/fuzzy
 * without rebuilding the whole `ResourceRepository` per keystroke. Registered on `platform.index`.
 *
 * The project's OWN `res/` roots are fed via [dev.ide.index.IndexScope.resourceRoots] (the in-memory source
 * side, edit-sensitive); immutable dependency/AAR `res/` is content-addressed onto disk segments via
 * [dev.ide.index.IndexScope.libraryResourceRoots] - so the bulk of a Material/AndroidX resource set is parsed
 * ONCE, shared across projects, and read on demand rather than held resident (a Material `values.xml` alone is
 * ~600 KB / thousands of declarations). The [inputFilter] accepts both SOURCE and LIBRARY res XML inputs.
 *
 * Parsed with a streaming SAX parser ([ResourceFileScanner]) - never a backtracking regex - so a huge
 * dependency `values.xml` is linear and bounded (the regex predecessor wedged the engine on ART).
 */
object AndroidResourceIndex : IndexExtension<String, ResourceDeclValue> {
    override val id = IndexId("android.resources")
    // v2: SAX scanner replaced the regex one (offsets are line-anchored; library resources now segment-cached).
    // v3: value resources also store their resolved literal value (for the completion hint, index-backed).
    override val version = 3
    override val keyDescriptor: KeyDescriptor<String> = ResourceKeyDescriptor
    override val valueExternalizer: Externalizer<ResourceDeclValue> = ResourceDeclExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY

    /** The index key for a resource: `"<type>/<name>"`. */
    fun key(type: String, name: String): String = "$type/$name"

    override val inputFilter = InputFilter { input ->
        // SOURCE = project res (in-memory side); LIBRARY = dependency/AAR res (disk segments). Both are res XML.
        (input.origin == IndexOrigin.SOURCE || input.origin == IndexOrigin.LIBRARY) &&
            input.sourcePath?.toString()?.replace('\\', '/')?.let {
                it.contains("/res/") && it.endsWith(".xml")
            } == true
    }

    override fun index(input: IndexInput): Map<String, Collection<ResourceDeclValue>> {
        val path = input.sourcePath?.toString() ?: return emptyMap()
        val text = input.text() ?: return emptyMap()
        val folder = input.sourcePath?.parent?.fileName?.toString() ?: return emptyMap()
        val out = HashMap<String, MutableList<ResourceDeclValue>>()
        for (d in ResourceFileScanner.scan(folder, path, text)) {
            out.getOrPut(key(d.type, d.name)) { ArrayList() }.add(d)
        }
        return out
    }
}

/**
 * Extracts resource declarations (type + name + offset) from one `res/` file's [text], given its [folder].
 *
 * Uses a streaming SAX parse (no regex). Offsets are anchored to the declaring element's line - precise enough
 * for go-to-definition and, unlike a backtracking regex, strictly linear on a multi-hundred-KB merged
 * `values.xml`. SAX fires its element events as it reads, so a malformed buffer still yields every declaration
 * parsed up to the error (the late exception is swallowed) - the tolerance the old regex gave, without the
 * pathological cost.
 */
object ResourceFileScanner {

    private val ID_BEARING = setOf(ResourceType.LAYOUT, ResourceType.MENU, ResourceType.NAVIGATION, ResourceType.DRAWABLE, ResourceType.XML, ResourceType.TRANSITION)
    private const val ID_PREFIX = "@+id/"

    /** Value resources whose element text is a useful completion hint (a literal, not a child-bearing element). */
    private val VALUE_TEXT_TYPES = setOf("string", "color", "dimen", "bool", "integer", "fraction")
    /** Hint values are length-capped so the index segment doesn't balloon on a large `values.xml`. */
    private const val MAX_VALUE_LEN = 60

    fun scan(folder: String, filePath: String, text: String): List<ResourceDeclValue> {
        val base = folder.substringBefore('-')
        val out = ArrayList<ResourceDeclValue>()
        if (base == "values") {
            runCatching { parse(text, ValuesHandler(filePath, text, out)) }
        } else {
            val type = ResourceType.fromFolder(base) ?: return out
            out += ResourceDeclValue(type.rClass, baseName(filePath), filePath, 0)
            // Only file-resource types that can host inline `@+id/…` declarations are scanned for them.
            if (type in ID_BEARING) runCatching { parse(text, IdHandler(filePath, text, out)) }
        }
        return out
    }

    private fun parse(text: String, handler: DefaultHandler) {
        newParser().parse(InputSource(StringReader(text)), handler)
    }

    /** Value-resource SAX handler: top-level `<resources>` children are declarations; `<declare-styleable>`'s
     *  child `<attr>`s are `R.attr` entries; `<style>`/array `<item>`s are deliberately ignored (not ids).
     *  A depth-1 declaration is emitted on its END tag so its element text (a value resource's literal) can be
     *  captured for the completion hint. */
    private class ValuesHandler(
        private val filePath: String,
        text: String,
        private val out: MutableList<ResourceDeclValue>,
    ) : DefaultHandler() {
        private val lines = LineOffsets(text)
        private var loc: Locator? = null
        private val stack = ArrayList<String>()
        /** The open depth-1 declaration whose text content we're accumulating (null between declarations). */
        private var pending: ResourceDeclValue? = null
        private val chars = StringBuilder()

        override fun setDocumentLocator(locator: Locator) { loc = locator }

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (stack.size) // depth 1 = a direct child of <resources>: the resource declaration itself (emitted on its close).
            {
                1 -> { pending = topLevelDecl(qName, attrs); chars.setLength(0) }
                // depth 2 under a <declare-styleable>: each child <attr name=…> is an R.attr entry.
                2 if stack[1] == "declare-styleable" && qName == "attr" ->
                    sanitize(attrs.getValue("name").orEmpty()).ifEmpty { null }
                        ?.let { out += ResourceDeclValue(ResourceType.ATTR.rClass, it, filePath, offset()) }
            }
            stack.add(qName)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (pending != null && chars.length < MAX_VALUE_LEN * 2) chars.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            // Closing a depth-1 declaration (stack = [resources, <decl>]): emit it, with text for a value type.
            if (stack.size == 2) pending?.let { p ->
                val value = if (p.type in VALUE_TEXT_TYPES) chars.toString().trim().take(MAX_VALUE_LEN).ifEmpty { null } else null
                out += if (value != null) p.copy(value = value) else p
                pending = null
            }
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        /** The declaration for a top-level `<resources>` child (value still null), or null if it isn't one. */
        private fun topLevelDecl(tag: String, attrs: Attributes): ResourceDeclValue? {
            val rawName = attrs.getValue("name") ?: return null
            // A namespaced name (`android:colorPrimary`) is a style/theme item referencing an attr, not a
            // resource declaration - resource names never contain ':'.
            if (':' in rawName) return null
            val name = sanitize(rawName).ifEmpty { return null }
            val type = when (tag) {
                // Only `<item type="…" name="…">` declares a resource; a bare `<item name=…>` is a style entry.
                "item" -> attrs.getValue("type")?.let { ResourceType.byRClass(it) }
                else -> ResourceType.fromValueTag(tag)
            } ?: return null
            return ResourceDeclValue(type.rClass, name, filePath, offset())
        }

        private fun offset(): Int = lines.offsetOf(loc?.lineNumber ?: 1)
    }

    /** File-resource SAX handler: records every `@+id/name` found in any attribute value. */
    private class IdHandler(
        private val filePath: String,
        text: String,
        private val out: MutableList<ResourceDeclValue>,
    ) : DefaultHandler() {
        private val lines = LineOffsets(text)
        private var loc: Locator? = null

        override fun setDocumentLocator(locator: Locator) { loc = locator }

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            for (i in 0 until attrs.length) {
                val v = attrs.getValue(i)
                if (!v.startsWith(ID_PREFIX)) continue
                val name = v.substring(ID_PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
                if (name.isNotEmpty()) out += ResourceDeclValue(ResourceType.ID.rClass, sanitize(name), filePath, lines.offsetOf(loc?.lineNumber ?: 1))
            }
        }
    }

    /** Maps a 1-based SAX line number to the character offset of that line's start. */
    private class LineOffsets(text: String) {
        private val starts = IntArray(text.count { it == '\n' } + 1).also { arr ->
            var idx = 1; arr[0] = 0
            for (i in text.indices) if (text[i] == '\n') arr[idx++] = i + 1
        }
        fun offsetOf(line: Int): Int = starts.getOrElse(line - 1) { 0 }
    }

    private fun baseName(filePath: String): String =
        sanitize(filePath.substringAfterLast('/').substringAfterLast('\\').substringBefore('.'))

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()

    /** Shared SAX factory (creation does service discovery - do it once); a fresh parser per file is cheap. */
    private val factory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    private fun newParser() = synchronized(factory) { factory.newSAXParser() }
}
