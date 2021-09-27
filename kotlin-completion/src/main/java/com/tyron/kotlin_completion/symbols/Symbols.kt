package com.tyron.kotlin_completion.symbols

import com.tyron.kotlin_completion.model.DocumentSymbol
import com.tyron.kotlin_completion.model.SymbolKind
import com.tyron.kotlin_completion.position.Position
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*
import java.lang.IllegalArgumentException

private fun documentSymbols(file: KtFile) : List<DocumentSymbol> {
    return doDocumentSymbols(file)
}


private fun doDocumentSymbols(element: PsiElement) : List<DocumentSymbol>{
    val children = element.children.flatMap(::doDocumentSymbols)

    return pickImportantElements(element, true)?.let {
        val file = element.containingFile
        val span = Position.range(file.text, it.textRange)
        val nameIdentifier = it.nameIdentifier
        val nameSpan = nameIdentifier?.let { Position.range(file.text, it.textRange) } ?: span
        val symbol = DocumentSymbol(it.name ?: "<anonymous>", symbolKind(it), span, nameSpan, null, children)
        listOf(symbol)
    } ?: children
}

private fun pickImportantElements(node: PsiElement, includeLocals: Boolean) : KtNamedDeclaration? =
    when (node) {
        is KtClassOrObject -> if (node.name == null) null else node
        is KtTypeAlias -> node
        is KtConstructor<*> -> node
        is KtNamedFunction -> if (!node.isLocal || includeLocals) node else null
        is KtProperty -> if (!node.isLocal || includeLocals) node else null
        is KtVariableDeclaration -> if (includeLocals) node else null
        else -> null
    }

private fun symbolKind(d: KtNamedDeclaration) : SymbolKind =
    when (d) {
        is KtClassOrObject -> SymbolKind.Class
        is KtTypeAlias -> SymbolKind.Interface
        is KtConstructor<*> -> SymbolKind.Constructor
        is KtNamedFunction -> SymbolKind.Function
        is KtProperty -> SymbolKind.Property
        is KtVariableDeclaration -> SymbolKind.Variable
        else -> throw IllegalArgumentException("Unexpected symbol $d")
    }
class Symbols {

}