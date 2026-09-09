package dev.ide.lang.xml.folding

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.xml.PsiXmlProjection
import dev.ide.vfs.VirtualFile

/**
 * Code folding for XML: collapses each element's body (`<tag>…</tag>` → `<tag>...</tag>`) and each block
 * comment. Ranges come straight from the PSI (exact `XmlTagValue` / `XmlComment` spans via
 * [PsiXmlProjection.folds]); the editor drops single-line / zero-width regions, so nothing needs pre-filtering.
 *
 * It re-parses the buffer text to PSI (cheap for XML) rather than reconstructing body ranges from the neutral
 * DOM, since PSI exposes the between-tags content range precisely. [parseOf] yields the cached parse so the
 * text matches what the editor displays.
 */
class XmlFoldingService(private val parseOf: suspend (VirtualFile) -> ParsedFile?) :
    FoldingService {

    override suspend fun folds(file: VirtualFile): List<FoldRegion> {
        val text = parseOf(file)?.text() ?: return emptyList()
        return PsiXmlProjection.folds(file.path, text)
    }
}
