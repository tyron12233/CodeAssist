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
import java.io.DataInput
import java.io.DataOutput

/** A resource declaration found in a `res/` file: its type, name, declaring file, and offset (for go-to). */
data class ResourceDeclValue(val type: String, val name: String, val filePath: String, val offset: Int)

object ResourceDeclExternalizer : Externalizer<ResourceDeclValue> {
    override fun write(out: DataOutput, value: ResourceDeclValue) {
        out.writeUTF(value.type); out.writeUTF(value.name); out.writeUTF(value.filePath); out.writeInt(value.offset)
    }
    override fun read(inp: DataInput): ResourceDeclValue =
        ResourceDeclValue(inp.readUTF(), inp.readUTF(), inp.readUTF(), inp.readInt())
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
 * without rebuilding the whole `ResourceRepository` per keystroke. Registered on `platform.index`; fed the
 * project's `res/` roots via `IndexScope.resourceRoots`, and updated per edit via `IndexService.reindexSource`.
 *
 * Pure text scanning (`ResourceFileScanner`) so it runs identically on desktop and ART.
 */
object AndroidResourceIndex : IndexExtension<String, ResourceDeclValue> {
    override val id = IndexId("android.resources")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = ResourceKeyDescriptor
    override val valueExternalizer: Externalizer<ResourceDeclValue> = ResourceDeclExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY

    /** The index key for a resource: `"<type>/<name>"`. */
    fun key(type: String, name: String): String = "$type/$name"
    override val inputFilter = InputFilter { input ->
        input.origin == IndexOrigin.SOURCE && input.sourcePath?.toString()?.replace('\\', '/')?.let {
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

/** Extracts resource declarations (type + name + offset) from one `res/` file's [text], given its [folder]. */
object ResourceFileScanner {

    // <tag ... name="..."> with the tag and the name's position; covers value resources + declare-styleable.
    private val VALUE_DECL = Regex("""<([\w-]+)\b([^>]*?)\bname\s*=\s*"([^"]+)"""")
    private val TYPE_ATTR = Regex("""\btype\s*=\s*"([^"]+)"""")
    private val ID_DECL = Regex("""@\+id/([A-Za-z_][\w.]*)""")
    private val ID_BEARING = setOf(ResourceType.LAYOUT, ResourceType.MENU, ResourceType.NAVIGATION, ResourceType.DRAWABLE, ResourceType.XML, ResourceType.TRANSITION)

    fun scan(folder: String, filePath: String, text: String): List<ResourceDeclValue> {
        val base = folder.substringBefore('-')
        val out = ArrayList<ResourceDeclValue>()
        if (base == "values") {
            for (m in VALUE_DECL.findAll(text)) {
                val tag = m.groupValues[1]
                val attrs = m.groupValues[2]
                val rawName = m.groupValues[3]
                // A namespaced name (`android:colorPrimary`) is a style/theme *item* referencing an attr, not a
                // resource declaration — skip it (resource names never contain ':').
                if (':' in rawName) continue
                val type = when (tag) {
                    // Only `<item type="…" name="…">` declares a resource. A bare `<item name="…">` is a `<style>`
                    // entry or an array element, NOT an id — defaulting it to id produced bogus `@id/…` entries.
                    "item" -> TYPE_ATTR.find(attrs)?.groupValues?.get(1)?.let { ResourceType.byRClass(it) }
                    else -> ResourceType.fromValueTag(tag)
                } ?: continue
                // A declare-styleable's child `<attr name=>`s are caught by this same loop (tag "attr" → ATTR).
                out += ResourceDeclValue(type.rClass, sanitize(rawName), filePath, m.range.first)
            }
        } else {
            val type = ResourceType.fromFolder(base)
            if (type != null) {
                out += ResourceDeclValue(type.rClass, baseName(filePath), filePath, 0)
                if (type in ID_BEARING) {
                    for (m in ID_DECL.findAll(text)) out += ResourceDeclValue(ResourceType.ID.rClass, sanitize(m.groupValues[1]), filePath, m.range.first)
                }
            }
        }
        return out
    }

    private fun baseName(filePath: String): String =
        sanitize(filePath.substringAfterLast('/').substringAfterLast('\\').substringBefore('.'))

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()
}
