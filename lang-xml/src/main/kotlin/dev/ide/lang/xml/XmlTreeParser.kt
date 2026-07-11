package dev.ide.lang.xml

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.incremental.DocumentSnapshot

/**
 * The XML parser façade. Historically a hand-written tolerant parser; now a thin adapter over IntelliJ's real
 * XML PSI: it parses [snapshot] with [PsiXmlProjection] (which drives `XMLParserDefinition` on the shared
 * [dev.ide.psi.IntellijPsiHost]) and returns the same backend-neutral [XmlNode] tree + well-formedness
 * [Diagnostic]s the rest of the codebase and the layout preview already consume.
 *
 * The constructor and [parse] signature are unchanged so every caller (the analyzer's incremental parser and
 * `layout-preview-impl`'s `LayoutInflater`/`ProjectPreviewResources`, which build one directly) is untouched.
 * PSI is error-tolerant, so a whole-file [XmlNode] tree is always produced even on the half-typed buffer.
 */
class XmlTreeParser(private val snapshot: DocumentSnapshot) {

    fun parse(): Pair<XmlNode, List<Diagnostic>> =
        PsiXmlProjection.parse(snapshot.file.path, snapshot.text)
}
