package dev.ide.kotlin.syntax.psi

import dev.ide.kotlin.syntax.KotlinSyntax
import org.jetbrains.kotlin.kmp.lexer.KDocTokens
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.tree.LightNode

/**
 * The inside of a doc comment, mirroring `org.jetbrains.kotlin.kdoc.psi`.
 *
 * **A doc comment is one token to the file parse, on purpose.** The grammar treats `/** … */` as a single
 * DOC_COMMENT the way it treats a lazily-parsed block as a single BLOCK, and the compiler keeps a SEPARATE
 * grammar for the contents ([KotlinSyntax.parseKDoc], and a second one again for the inside of a `[link]`).
 * So the structure below is reached by parsing the comment's text on demand, which is why these elements come
 * from their own [KtTreeSession] rather than from the file's.
 *
 * That sub-tree numbers its offsets from the comment's own start, because a token carries the offset it was
 * lexed at. [KtTreeSession.baseOffset] carries the shift, so everything here reports FILE coordinates and a
 * caller cannot forget to add them.
 *
 * On demand and cached per element: the overwhelming majority of doc comments are never asked about, and
 * parsing every one during a file parse would pay for all of them to serve the few that are.
 */
class KDoc internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    /** The `@param x …` / `@return …` groups, plus the leading prose section. */
    val sections: List<KDocSection> get() = childrenOfType()

    /** Every tag in the comment, across sections. */
    val tags: List<KDocTag> get() = sections.flatMap { it.tags }
}

/** One section of a doc comment: the prose, or a group started by a section tag. */
class KDocSection internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val tags: List<KDocTag> get() = childrenOfType()
}

/** A `@param name description` block. */
class KDocTag internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    /** `param` in `@param x`, without the `@`. */
    val name: String? get() = childrenWithTrivia.firstOrNull { it.elementType == KDocTokens.TAG_NAME }
        ?.text?.removePrefix("@")

    /** The `[Foo.bar]` links inside this tag. */
    val links: List<KDocLink> get() = childrenOfType()
}

/**
 * A `[Foo.bar]` markdown link.
 *
 * The link's INSIDE is a third parse: the text between the brackets is Kotlin, lexed with the Kotlin lexer
 * rather than the KDoc one, which is why [names] is another sub-session and not a child walk.
 */
class KDocLink internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val names: List<KDocName> get() = parsedNames()

    /** The whole referenced name, `Foo.bar`, or null when the link holds nothing nameable. */
    val referencedName: String? get() = names.lastOrNull()?.text

    private fun parsedNames(): List<KDocName> {
        val inner = KotlinSyntax.parseKDocLink(text)
        val sub = KtTreeSession(inner, session.fileName, baseOffset = textOffset)
        val root = sub.claimRoot(inner.getRoot(), KDocLink(sub, inner.getRoot()))
        return root.descendants().filterIsInstance<KDocName>().toList()
    }
}

/** One qualified-name step inside a `[link]`. */
class KDocName internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node)

/**
 * The parsed contents of this doc comment, or null when this element is not one.
 *
 * `docComment` gives the DOC_COMMENT token; this gives its structure.
 */
val KtElement.parsedKDoc: KDoc?
    get() {
        if (elementType != KtTokens.DOC_COMMENT) return null
        return session.kdoc(node.index) {
            val tree = KotlinSyntax.parseKDoc(text)
            val sub = KtTreeSession(tree, session.fileName, baseOffset = textOffset)
            sub.claimRoot(tree.getRoot(), KDoc(sub, tree.getRoot()))
        }
    }
