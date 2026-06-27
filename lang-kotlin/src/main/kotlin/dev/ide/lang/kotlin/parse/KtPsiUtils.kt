package dev.ide.lang.kotlin.parse

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Generic Kotlin-PSI navigation helpers shared by the editor front-ends (analyzer, completion,
 * signature help). They carry no resolution or symbol-model knowledge, so any feature can reuse them.
 */

/** The nearest ancestor of [start] (inclusive) that is a [T], or null. */
inline fun <reified T> climbTo(start: PsiElement?): T? {
    var n = start
    while (n != null) {
        if (n is T) return n
        n = n.parent
    }
    return null
}

/** Whether any strict ancestor of [element] satisfies [predicate]. */
inline fun hasAncestor(element: PsiElement, predicate: (PsiElement) -> Boolean): Boolean {
    var p: PsiElement? = element.parent
    while (p != null) { if (predicate(p)) return true; p = p.parent }
    return false
}

/** Strip enclosing parentheses (`(super).foo` to `super`) so an expression is classified by its real node. */
fun unwrapParen(expr: KtExpression): KtExpression {
    var e: KtExpression = expr
    while (e is KtParenthesizedExpression) e = e.expression ?: return e
    return e
}

/** Whether [element] sits inside a type reference, so a lower-case generic type parameter is not mistaken
 *  for a value reference. Stops at the enclosing declaration. */
fun inTypeReference(element: PsiElement): Boolean {
    var p: PsiElement? = element.parent
    while (p != null && p !is KtDeclaration) { if (p is KtTypeReference) return true; p = p.parent }
    return false
}

/** The identifier characters immediately before [offset] in [text] (the prefix being completed). */
fun identifierPrefixBefore(text: CharSequence, offset: Int): String {
    var i = offset
    while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
    return text.subSequence(i, offset).toString()
}

/** Simple names of every `typealias` declared anywhere in [file] (the live buffer the disk model may lag). */
fun typeAliasNamesIn(file: KtFile): Set<String> {
    val out = HashSet<String>()
    fun rec(p: PsiElement) {
        if (p is KtTypeAlias) p.name?.let { out += it }
        var c = p.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    rec(file)
    return out
}
