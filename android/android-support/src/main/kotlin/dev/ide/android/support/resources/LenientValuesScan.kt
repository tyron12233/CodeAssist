package dev.ide.android.support.resources

import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlTreeParser

/**
 * Recovers value-resource declarations from a MALFORMED `values` XML file using the error-tolerant XML PSI
 * ([XmlTreeParser]) — the fallback for when the streaming SAX parse fails. SAX abandons a file at its FIRST
 * error, losing every declaration after it, so a single stray/unclosed tag while editing would otherwise wipe
 * most of `R.string`/`R.color`/… (and, if that's the only file of its type, the whole `R.<type>`). PSI is
 * error-tolerant — it always yields a whole-file tree, recovering unclosed/mismatched tags — so every
 * `<tag name="…">` under `<resources>` is recovered regardless of well-formedness, and completion + the
 * synthetic `R` survive a broken file. Runs ONLY on the file SAX rejected — the huge, always-well-formed
 * dependency `values.xml` stays on the fast SAX path (a full PSI tree per such file would be far heavier).
 */
internal object LenientValuesScan {

    /** A recovered declaration: its [type], sanitized [name], and the character [offset] of its start tag. */
    data class Decl(val type: ResourceType, val name: String, val offset: Int)

    fun scan(filePath: String, text: CharSequence): List<Decl> {
        val root = runCatching { XmlTreeParser.parse(filePath, text) }.getOrNull() ?: return emptyList()
        val resources = root.childTags.firstOrNull { it.name == "resources" } ?: return emptyList()
        val out = ArrayList<Decl>()
        for (el in resources.childTags) {
            val tag = el.name ?: continue
            val rawName = attr(el, "name") ?: continue
            // A namespaced name (`android:colorPrimary`) is a style/theme item referencing an attr, not a
            // resource declaration — resource names never contain ':'.
            if (':' in rawName) continue
            val type = when (tag) {
                // Only `<item type="…" name="…">` declares a resource; a bare `<item name=…>` is a style entry.
                "item" -> attr(el, "type")?.let { ResourceType.byRClass(it) }
                else -> ResourceType.fromValueTag(tag)
            } ?: continue
            val name = sanitize(rawName)
            if (name.isNotEmpty()) out += Decl(type, name, el.range.start)
            // A `<declare-styleable>`'s child `<attr name=…>` are R.attr entries (same as the SAX handler).
            if (tag == "declare-styleable") for (child in el.childTags) {
                if (child.name != "attr") continue
                attr(child, "name")?.let { sanitize(it) }?.takeIf { it.isNotEmpty() }
                    ?.let { out += Decl(ResourceType.ATTR, it, child.range.start) }
            }
        }
        return out
    }

    /** The value of [el]'s [name] attribute (the text between the quotes), or null when absent. */
    private fun attr(el: XmlNode, name: String): String? =
        el.attributes.firstOrNull { it.name == name }?.valueNode?.text()?.toString()

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()
}
