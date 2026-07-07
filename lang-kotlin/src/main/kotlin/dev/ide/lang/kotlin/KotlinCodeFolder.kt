package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile

/**
 * Kotlin code folding off the parse-only PSI (no resolution needed). One walk over the live parse emitting a
 * [FoldRegion] for each foldable structure:
 *  - the import group → collapses to `import ...` (collapsed by default, like IntelliJ);
 *  - class/object/interface/enum bodies and function/lambda/control-flow blocks → `{...}` (the braces stay
 *    visible around the placeholder, since the region spans only the text BETWEEN them);
 *  - multi-line block comments / KDoc → `/*...*/`.
 *
 * Only regions that actually span more than one line are emitted (a single-line block can't usefully fold).
 * Polls [EngineCancellation] between nodes so completion can preempt the pass; the host retries.
 */
class KotlinCodeFolder(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
) : FoldingService {

    override suspend fun folds(file: VirtualFile): List<FoldRegion> {
        val parsed = parsedFor(file) ?: return emptyList()
        val text = parsed.ktFile.text
        val out = ArrayList<FoldRegion>(32)

        // The import group: from the first import directive to the last, shown as `import ...`.
        parsed.ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { imports ->
            val start = imports.first().textRange.startOffset
            val end = imports.last().textRange.endOffset
            addRegion(out, text, start, end, "import ...", FoldKind.IMPORTS, collapsedByDefault = true)
        }

        var seen = 0
        fun walk(psi: PsiElement) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            when (psi) {
                is KtClassBody -> braceBlock(out, text, psi.lBrace, psi.rBrace, FoldKind.CLASS_BODY)
                is KtBlockExpression -> braceBlock(out, text, psi.lBrace, psi.rBrace, FoldKind.FUNCTION_BODY)
                is KDoc -> addRegion(out, text, psi.textRange.startOffset, psi.textRange.endOffset, "/**...*/", FoldKind.COMMENT)
                is PsiComment -> if (psi.tokenType == org.jetbrains.kotlin.lexer.KtTokens.BLOCK_COMMENT)
                    addRegion(out, text, psi.textRange.startOffset, psi.textRange.endOffset, "/*...*/", FoldKind.COMMENT)
                else -> {}
            }
            var c = psi.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(parsed.ktFile)
        return out
    }

    /** A `{ … }` block: fold the text strictly BETWEEN the braces so `{` and `}` stay visible → `{...}`. */
    private fun braceBlock(out: MutableList<FoldRegion>, text: CharSequence, lBrace: PsiElement?, rBrace: PsiElement?, kind: FoldKind) {
        if (lBrace == null || rBrace == null) return
        addRegion(out, text, lBrace.textRange.endOffset, rBrace.textRange.startOffset, "...", kind)
    }

    /** Emit a region only when it spans more than one line (a same-line block isn't worth a fold). */
    private fun addRegion(out: MutableList<FoldRegion>, text: CharSequence, start: Int, end: Int, placeholder: String, kind: FoldKind, collapsedByDefault: Boolean = false) {
        if (end <= start || end > text.length) return
        var multiline = false
        for (i in start until end) if (text[i] == '\n') { multiline = true; break }
        if (!multiline) return
        out += FoldRegion(TextRange(start, end), placeholder, kind, collapsedByDefault)
    }
}
