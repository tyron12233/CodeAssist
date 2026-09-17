package dev.ide.kotlin.syntax.psi

/**
 * The tree-walking helpers, mirroring `org.jetbrains.kotlin.psi.psiUtil` and `PsiTreeUtil`.
 *
 * There are only eight of these in the whole editor backend, across about sixty call sites, which is the
 * smallest and most surprising part of the PSI surface a port has to reproduce. They are here with the
 * compiler's names so those call sites need an import change and nothing else.
 */

/**
 * The nearest ancestor of type [T], this element included.
 *
 * [T] is not bounded by [KtElement] because the useful targets include the bases that are interfaces —
 * `getParentOfType<KtTypeParameterListOwner>()` is how the backend walks out to whatever declares a type
 * parameter, and that is not a class.
 */
inline fun <reified T : Any> KtPsiElement.getParentOfType(strict: Boolean = false): T? {
    var current: KtPsiElement? = if (strict) parent else this
    while (current != null) {
        if (current is T) return current
        current = current.parent
    }
    return null
}

/** The nearest ancestor of type [T], NOT counting this element. */
inline fun <reified T : Any> KtPsiElement.getStrictParentOfType(): T? = getParentOfType<T>(strict = true)

/**
 * The class or object this declaration is a member of, or null for a top-level or local one.
 *
 * Three shapes reach a class, not one: a member's parent is the class BODY; an enum entry and a primary
 * constructor hang off the class directly; and a primary-constructor property is a parameter, so it reaches
 * the class through the parameter list. A declaration inside a function body is local and has no containing
 * class even when a class is further up, which is why this is these four cases and not a walk.
 */
val KtDeclaration.containingClassOrObject: KtClassOrObject?
    get() = when (val owner = parent) {
        is KtClassBody -> owner.parent as? KtClassOrObject
        is KtClassOrObject -> owner
        is KtParameterList -> (owner.parent as? KtPrimaryConstructor)?.parent as? KtClassOrObject
        else -> null
    }

/** Ancestors, innermost first. */
val KtPsiElement.parents: Sequence<KtElement>
    get() = generateSequence(parent) { it.parent }

/** Siblings after this one, in source order. */
val KtElement.siblings: Sequence<KtElement>
    get() = generateSequence(nextSibling) { it.nextSibling }

/** Siblings before this one, nearest first. */
val KtElement.prevSiblings: Sequence<KtElement>
    get() = generateSequence(prevSibling) { it.prevSibling }

/** Every descendant of type [T], depth first, this element included. */
inline fun <reified T : KtElement> KtPsiElement.collectDescendantsOfType(): List<T> =
    descendants().filterIsInstance<T>().toList()

/** The first descendant of type [T], or null. */
inline fun <reified T : KtElement> KtPsiElement.findDescendantOfType(): T? =
    descendants().filterIsInstance<T>().firstOrNull()

/** This element and every descendant, depth first, trivia excluded. */
fun KtPsiElement.descendants(): Sequence<KtElement> = sequence {
    // Every implementation IS a KtElement; the interface exists so a value typed as one of the abstract
    // bases can still be walked, not because anything else can be in a tree.
    (this@descendants as? KtElement)?.let { yield(it) }
    for (child in children) yieldAll(child.descendants())
}

/** Does the subtree contain a region the parser could not read? */
fun KtElement.hasErrorElements(): Boolean =
    descendants().any { it.elementType == com.intellij.platform.syntax.element.SyntaxTokenTypes.ERROR_ELEMENT }

/**
 * The innermost element covering [offset], which is what a caret-position query needs.
 *
 * Walks down rather than consulting an index: the light tree has no offset lookup, and a caret query is one
 * descent per call, not a hot loop.
 */
fun KtFile.findElementAt(offset: Int): KtElement? {
    var current: KtElement = this
    if (offset !in current.textRange) return null
    while (true) {
        val next = current.children.firstOrNull { offset in it.textRange } ?: return current
        current = next
    }
}

/** The innermost element of type [T] covering [offset]. */
inline fun <reified T : KtElement> KtFile.findElementOfTypeAt(offset: Int): T? =
    findElementAt(offset)?.getParentOfType<T>()

/** Every element in the file, depth first, trivia excluded. */
fun KtFile.allElements(): Sequence<KtElement> = descendants()
