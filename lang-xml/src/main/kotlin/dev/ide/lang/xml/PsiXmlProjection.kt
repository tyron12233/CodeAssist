package dev.ide.lang.xml

import com.intellij.lang.xml.BackendXmlElementFactory
import com.intellij.lang.xml.BasicXmlElementFactory
import com.intellij.lang.xml.XMLLanguage
import com.intellij.lang.xml.XMLParserDefinition
import com.intellij.lang.xml.XmlASTFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlDoctype
import com.intellij.psi.xml.XmlDocument
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlProcessingInstruction
import com.intellij.psi.xml.XmlProlog
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import com.intellij.psi.xml.StartTagEndTokenProvider
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.psi.IntellijPsiHost

/**
 * Parses XML with IntelliJ's real XML PSI ([IntellijPsiHost] + `XMLParserDefinition`) and projects the
 * resulting `XmlFile` onto the backend-neutral [XmlNode]/[XmlParsedFile] DOM the rest of the codebase (and
 * the layout preview) consumes. PSI is the source of truth; this projection is a thin, eager view built once
 * per parse (XML files are small), preserving every offset/kind invariant the old hand-written parser
 * guaranteed so no consumer changes.
 *
 * Invariants reproduced (verified against the desktop + ART spikes):
 *  - `DOCUMENT` root always spans `[0, length)`, even on malformed input (PSI is error-tolerant).
 *  - `TAG` range starts at `<`; `TAG.name` is the bare (possibly prefixed) tag name; `selfClosed` for `<…/>`.
 *  - `ATTRIBUTE` range covers the whole `name="value"`; `ATTRIBUTE.name` is the prefixed attribute name.
 *  - `ATTR_VALUE` range is the text strictly BETWEEN the quotes (`XmlAttributeValue.valueTextRange`).
 *  - children are in source order; well-formedness diagnostics come from `PsiErrorElement`s.
 *
 * The tree is walked via raw PSI children (firstChild/nextSibling), NOT `XmlTag.getSubTags()` — the latter
 * runs XInclude gating through `FileBasedIndex` (a service this headless host doesn't stand up), and Android
 * XML has no XInclude anyway.
 */
internal object PsiXmlProjection {

    @Volatile
    private var registered = false

    private fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            IntellijPsiHost.registerLanguage(
                XMLLanguage.INSTANCE,
                XMLParserDefinition(),
                XmlASTFactory()
            )
            IntellijPsiHost.registerAppService(
                BasicXmlElementFactory::class.java,
                BackendXmlElementFactory()
            )
            // XmlTag.getValue() (used for folding) consults this EP to find the start tag's end; register it
            // empty so the lookup returns no providers instead of "Missing extension point".
            IntellijPsiHost.registerApplicationExtensionPoint(
                "com.intellij.xml.startTagEndToken", StartTagEndTokenProvider::class.java,
            )
            registered = true
        }
    }

    /** Parse [text] (named [path] for PSI's file-name; the extension is cosmetic since we pass the Language). */
    fun parse(path: String, text: CharSequence): Pair<XmlNode, List<Diagnostic>> {
        ensureRegistered()
        val file = IntellijPsiHost.parse(fileNameFor(path), XMLLanguage.INSTANCE, text) as XmlFile
        val root = XmlNode(XmlNodeKinds.DOCUMENT, 0, text.length, text)
        addChildren(file, root, text)
        root.close(text.length)
        return root to collectDiagnostics(file)
    }

    /** Append the mapped children of [psi] to [parent], recursing through transparent containers. */
    private fun addChildren(psi: PsiElement, parent: XmlNode, source: CharSequence) {
        var child = psi.firstChild
        while (child != null) {
            val node = mapElement(child, source)
            when {
                node != null -> {
                    parent.add(node); addChildren(child, node, source)
                }
                // XmlDocument / XmlProlog are structural wrappers with no DOM node of their own — recurse
                // through them so their prolog/doctype/root-tag surface as children of [parent].
                child is XmlDocument || child is XmlProlog -> addChildren(child, parent, source)
                // else: a leaf token (`<`, tag name, `>`, `=`, quotes) or whitespace — nothing to project.
            }
            child = child.nextSibling
        }
    }

    /** Map a single PSI element to its neutral [XmlNode], or null when it is not a projected kind. */
    private fun mapElement(psi: PsiElement, source: CharSequence): XmlNode? {
        val r = psi.textRange
        return when (psi) {
            is XmlTag -> XmlNode(
                XmlNodeKinds.TAG,
                r.startOffset,
                r.endOffset,
                source,
                name = psi.name
            )
                .also {
                    it.selfClosed =
                        psi.node?.findChildByType(XmlTokenType.XML_EMPTY_ELEMENT_END) != null
                }

            is XmlAttribute -> XmlNode(
                XmlNodeKinds.ATTRIBUTE,
                r.startOffset,
                r.endOffset,
                source,
                name = psi.name
            )

            is XmlAttributeValue -> {
                // valueTextRange is the absolute range of the text BETWEEN the quotes.
                val v = psi.valueTextRange
                XmlNode(XmlNodeKinds.ATTR_VALUE, v.startOffset, v.endOffset, source)
            }

            is XmlComment -> XmlNode(XmlNodeKinds.COMMENT, r.startOffset, r.endOffset, source)
            is XmlProcessingInstruction -> XmlNode(
                XmlNodeKinds.PROLOG,
                r.startOffset,
                r.endOffset,
                source
            )

            is XmlDoctype -> XmlNode(XmlNodeKinds.DOCTYPE, r.startOffset, r.endOffset, source)
            is XmlText -> XmlNode(XmlNodeKinds.TEXT, r.startOffset, r.endOffset, source)
            is PsiErrorElement -> XmlNode(NodeKind.ERROR, r.startOffset, r.endOffset, source)
            else -> null
        }
    }

    /**
     * Foldable regions, computed directly on the PSI (whose element ranges are exact): each `XmlTag` body
     * (`XmlTagValue` range, i.e. between `>` and `</…>`) and each `XmlComment`. The host drops single-line /
     * zero-width regions, so no line-count pre-filter is needed here.
     */
    fun folds(path: String, text: CharSequence): List<FoldRegion> {
        ensureRegistered()
        val file = IntellijPsiHost.parse(fileNameFor(path), XMLLanguage.INSTANCE, text) as XmlFile
        val out = ArrayList<FoldRegion>()
        // Walk the whole tree (raw children — never getSubTags, which touches FileBasedIndex/XInclude).
        val stack = ArrayDeque<PsiElement>()
        file.firstChild?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            when (cur) {
                is XmlTag -> {
                    val v = cur.value.textRange
                    if (v.length > 0) out += FoldRegion(
                        TextRange(v.startOffset, v.endOffset),
                        "...",
                        FoldKind.BLOCK
                    )
                }

                is XmlComment -> {
                    val r = cur.textRange
                    out += FoldRegion(
                        TextRange(r.startOffset, r.endOffset),
                        "<!--...-->",
                        FoldKind.COMMENT
                    )
                }
            }
            var c = cur.firstChild
            while (c != null) {
                stack.addLast(c); c = c.nextSibling
            }
        }
        return out
    }

    /** Well-formedness diagnostics = every `PsiErrorElement` in the tree (the tolerant parser's recovery points). */
    private fun collectDiagnostics(file: XmlFile): List<Diagnostic> =
        PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { err ->
            val r = err.textRange
            Diagnostic(
                TextRange(r.startOffset, r.endOffset),
                Severity.ERROR,
                err.errorDescription,
                "xml.syntax"
            )
        }

    /** A PSI file name ending in `.xml` (the extension is cosmetic — parsing uses [XMLLanguage] directly). */
    private fun fileNameFor(path: String): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        return if (base.endsWith(".xml")) base else "file.xml"
    }
}
