package dev.ide.kotlin.syntax.psi

import dev.ide.kotlin.syntax.tree.AstNode

/**
 * The tree-walking helpers, mirroring `org.jetbrains.kotlin.psi.psiUtil` and `PsiTreeUtil`.
 *
 * There are only eight of these in the whole editor backend, across about sixty call sites, which is the
 * smallest and most surprising part of the PSI surface a port has to reproduce. They are here with the
 * compiler's names so those call sites need an import change and nothing else.
 */

/** The nearest ancestor of type [T], this element included. */
inline fun <reified T : KtElement> KtElement.getParentOfType(strict: Boolean = false): T? {
    var current: KtElement? = if (strict) parent else this
    while (current != null) {
        if (current is T) return current
        current = current.parent
    }
    return null
}

/** The nearest ancestor of type [T], NOT counting this element. */
inline fun <reified T : KtElement> KtElement.getStrictParentOfType(): T? = getParentOfType<T>(strict = true)

/** Ancestors, innermost first. */
val KtElement.parents: Sequence<KtElement>
    get() = generateSequence(parent) { it.parent }

/** Siblings after this one, in source order. */
val KtElement.siblings: Sequence<KtElement>
    get() = generateSequence(nextSibling) { it.nextSibling }

/** Siblings before this one, nearest first. */
val KtElement.prevSiblings: Sequence<KtElement>
    get() = generateSequence(prevSibling) { it.prevSibling }

/** Every descendant of type [T], depth first, this element included. */
inline fun <reified T : KtElement> KtElement.collectDescendantsOfType(): List<T> =
    node.descendants().filter { !it.isTrivia() }.map { it.toPsi() }.filterIsInstance<T>().toList()

/** The first descendant of type [T], or null. */
inline fun <reified T : KtElement> KtElement.findDescendantOfType(): T? =
    node.descendants().filter { !it.isTrivia() }.map { it.toPsi() }.filterIsInstance<T>().firstOrNull()

/** Does the subtree contain a region the parser could not read? */
fun KtElement.hasErrorElements(): Boolean =
    node.descendants().any { it.elementType === dev.ide.kotlin.syntax.tree.TokenType.ERROR_ELEMENT }

/** The innermost element covering [offset], which is what a caret-position query needs. */
fun KtFile.findElementAt(offset: Int): KtElement? = node.findElementAt(offset)?.toPsi()

/** The innermost element of type [T] covering [offset]. */
inline fun <reified T : KtElement> KtFile.findElementOfTypeAt(offset: Int): T? =
    findElementAt(offset)?.getParentOfType<T>()

/** Every element in the file, depth first, trivia excluded. */
fun KtFile.allElements(): Sequence<KtElement> =
    node.descendants().filter { !it.isTrivia() }.map { it.toPsi() }

/** The node's text with the surrounding backticks removed, if it has any. */
internal fun AstNode.unquoted(): String = text.removeSurrounding("`")
