package dev.ide.lang.xml.hints

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile

/**
 * Resolves a local resource reference to a short display value for an inlay hint - a string's text, a color's
 * `#RRGGBB`, a dimen's `16dp`. lang-xml owns *where* a hint goes (the [XmlInlayHintService] walk); the host
 * owns *what a reference resolves to* (the Android resource index). Returns null when the reference is
 * unresolved (or has no literal value, e.g. a file resource), in which case no hint is shown.
 */
fun interface XmlResourceValueResolver {
    fun resolve(rClass: String, name: String): String?
}

/**
 * Inlay hints for Android XML: the resolved VALUE of a local resource reference, shown inline after it -
 * `android:text="@string/app_name"`‹CodeAssist› - so the editor reads like the rendered UI without chasing
 * every `@string`/`@color`/`@dimen` to its definition. The value comes from the injected
 * [XmlResourceValueResolver] (the incremental resource index in production), never from a per-keystroke parse.
 *
 * Only LOCAL value references resolve: framework `@android:…` / other-package refs and `@+id` declarations are
 * skipped (the resolver has no literal for them). Pure over the tolerant DOM otherwise, so lang-xml stays free
 * of resource knowledge.
 */
class XmlInlayHintService(
    private val parseOf: suspend (VirtualFile) -> ParsedFile?,
    private val resolver: XmlResourceValueResolver,
) : InlayHintService {

    override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> {
        val parsed = parseOf(file) ?: return emptyList()
        val out = ArrayList<InlayHint>()
        var seen = 0
        fun walk(node: DomNode) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            if (node is XmlNode && node.kind == XmlNodeKinds.ATTR_VALUE && node.range.start <= range.end && node.range.end >= range.start) {
                hintFor(node)?.let { out += it }
            }
            node.children.forEach(::walk)
        }
        walk(parsed)
        return out
    }

    private fun hintFor(value: XmlNode): InlayHint? {
        val m = LOCAL_REF.matchEntire(value.text().toString().trim()) ?: return null
        val resolved = resolver.resolve(m.groupValues[1], sanitize(m.groupValues[2])) ?: return null
        val preview = preview(resolved) ?: return null
        // Anchor just past the value's closing quote (its range is the text between the quotes).
        return InlayHint(
            offset = value.range.end + 1,
            parts = listOf(InlayHintPart(preview)),
            kind = InlayHintKind.OTHER,
            tooltip = resolved,
            paddingLeft = true,
        )
    }

    /** A one-line, length-capped rendering of a resolved value, or null when it's blank. */
    private fun preview(value: String): String? {
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > 30) collapsed.take(29) + "…" else collapsed
    }

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_')

    private companion object {
        /** A LOCAL `@type/name` reference (no `@+` declaration, no `pkg:` / framework prefix). */
        val LOCAL_REF = Regex("""@(\w+)/([\w.]+)""")
    }
}
