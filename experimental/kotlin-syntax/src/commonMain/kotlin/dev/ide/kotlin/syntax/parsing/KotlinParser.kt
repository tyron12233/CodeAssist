package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.toPsi
import dev.ide.kotlin.syntax.tree.AstNode

/**
 * The entry point: Kotlin source in, a tree out.
 *
 * It never throws and never returns null. Whatever the input, the result covers the whole text, which is the
 * contract an editor needs — completion fires on code that is wrong by definition, halfway through being
 * typed.
 */
object KotlinParser {

    /** Parse [text] and return the raw tree, rooted at a Kotlin file element. */
    fun parse(text: CharSequence): AstNode {
        val builder = SyntaxTreeBuilderImpl(text)
        KotlinParsing(builder).parseFile()
        return builder.treeBuilt(KtNodeTypes.KT_FILE)
    }

    /**
     * Parse [text] and return it behind the `Kt*` facade, which is what callers normally want.
     *
     * It goes through the node's own wrapper rather than constructing one, so the file element has the same
     * identity here as it does when reached from anywhere inside the tree.
     */
    fun parseFile(text: CharSequence): KtFile = parse(text).toPsi() as KtFile
}
