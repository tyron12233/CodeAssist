package com.tyron.kotlin_completion

import com.tyron.kotlin_completion.compiler.CompletionKind
import com.tyron.kotlin_completion.completion.findParent
import com.tyron.kotlin_completion.position.Position.changedRegion
import com.tyron.kotlin_completion.util.PsiUtils
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.nio.file.Paths

/**
 * Parse the expression at `cursor`.
 *
 * If the `asReference` flag is set, the method will attempt to
 * convert a declaration (e.g. of a class or a function) to a referencing
 * expression before parsing it.
 */
fun parseAtPoint(
    classPath: CompilerClassPath,
    cursor: Int,
    oldCursor: Int,
    content: String,
    parse: KtFile,
    asReference: Boolean = false
): KtElement? {
    val oldChanged = changedRegion(parse.text, content)?.first ?: TextRange(cursor, cursor)
    val psi = parse.findElementAt(oldCursor) ?: return null
    val oldParent = psi.parentsWithSelf()
        .filterIsInstance<KtDeclaration>()
        .firstOrNull { it.textRange.contains(oldChanged) } ?: parse

    val (surroundingContent, offset) = contentAndOffsetFromElement(
        parse,
        content,
        psi,
        oldParent,
        asReference
    )

    val padOffset = " ".repeat(offset)
    val recompile = classPath.compiler.createKtFile(
        padOffset + surroundingContent,
        Paths.get("dummy.virtual.kt"),
        CompletionKind.DEFAULT
    )
    return recompile.findElementAt(cursor)?.findParent<KtElement>()
}

/**
 * Extracts the surrounding content and the text offset from a
 * PSI element.
 *
 * See `parseAtPoint` for documentation of the `asReference` flag.
 */
private fun contentAndOffsetFromElement(parse: KtFile, content: String, psi: PsiElement, parent: KtElement, asReference: Boolean): Pair<String, Int> {
    var surroundingContent: String
    var offset: Int

    if (asReference) {
        // Convert the declaration into a fake reference expression
        when {
            parent is KtClass && psi.node.elementType == KtTokens.IDENTIFIER -> {
                // Converting class name identifier: Use a fake property with the class name as type
                //                                   Otherwise the compiler/analyzer would throw an exception due to a missing TopLevelDescriptorProvider
                val prefix = "val x: "
                surroundingContent = prefix + psi.text
                offset = psi.textRange.startOffset - prefix.length

                return Pair(surroundingContent, offset)
            }
        }
    }

    // Otherwise just use the expression
    val recoveryRange = parent.textRange
   // Log.d("CompiledFile", "Re-parsing ${parse.name}")

    surroundingContent = content.substring(recoveryRange.startOffset, content.length - (parse.text.length - recoveryRange.endOffset))
    offset = recoveryRange.startOffset

    if (asReference && (parent as? KtParameter)?.hasValOrVar() == false) {
        // Prepend 'val' to (e.g. function) parameters
        val prefix = "val "
        surroundingContent = prefix + surroundingContent
        offset -= prefix.length
    }

    return Pair(surroundingContent, offset)
}

private fun PsiElement.parentsWithSelf() = PsiUtils.getParentsWithSelf(this)
