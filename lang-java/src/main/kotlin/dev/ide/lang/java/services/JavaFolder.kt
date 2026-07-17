package dev.ide.lang.java.services

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.vfs.VirtualFile

/**
 * Java code folding over the PSI: the import group (collapsed by default), class/method bodies, and
 * multi-line comments/Javadoc. Purely structural — no resolution.
 */
class JavaFolder(private val psiFor: (VirtualFile) -> PsiJavaFile) : FoldingService {

    override suspend fun folds(file: VirtualFile): List<FoldRegion> {
        val psi = psiFor(file)
        val out = ArrayList<FoldRegion>()

        // Import group: fold everything after the first `import` keyword through the last import.
        psi.importList?.allImportStatements?.takeIf { it.size >= 2 }?.let { imports ->
            val first = imports.first().textRange
            val last = imports.last().textRange
            val start = (first.startOffset + "import".length).coerceAtMost(last.endOffset)
            out += FoldRegion(TextRange(start, last.endOffset), " ...", FoldKind.IMPORTS, collapsedByDefault = true)
        }

        psi.accept(object : JavaRecursiveElementVisitor() {
            override fun visitClass(aClass: PsiClass) {
                braceFold(aClass.lBrace?.textRange?.endOffset, aClass.rBrace?.textRange?.startOffset, FoldKind.CLASS_BODY)
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                val body = method.body
                braceFold(body?.lBrace?.textRange?.endOffset, body?.rBrace?.textRange?.startOffset, FoldKind.FUNCTION_BODY)
                super.visitMethod(method)
            }

            override fun visitComment(comment: PsiComment) {
                val r = comment.textRange
                if (comment.text.contains('\n')) {
                    val placeholder = if (comment.text.startsWith("/**")) "/**...*/" else "/*...*/"
                    out += FoldRegion(TextRange(r.startOffset, r.endOffset), placeholder, FoldKind.COMMENT)
                }
                super.visitComment(comment)
            }

            private fun braceFold(open: Int?, close: Int?, kind: FoldKind) {
                if (open != null && close != null && close > open) {
                    out += FoldRegion(TextRange(open, close), "...", kind)
                }
            }
        })
        return out
    }
}
