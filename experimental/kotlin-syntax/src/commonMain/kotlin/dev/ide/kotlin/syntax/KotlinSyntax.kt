package dev.ide.kotlin.syntax

import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.parser.KotlinParser
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtTreeSession
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree
import org.jetbrains.kotlin.kmp.tree.buildLanguageSpecificLightTree

/**
 * Text in, a parse tree out, using the Kotlin compiler's own grammar.
 *
 * The parser underneath is vendored from `compiler/multiplatform-parsing` (see `vendor/VENDOR.md`), so the
 * tree this returns is the one the compiler produces, not an approximation of it. That is the whole design:
 * the expensive part of taking Kotlin analysis off the JVM was never the grammar, it was owning a tree whose
 * shape 18,000 lines of the editor backend already assume. Borrowing the grammar removes the question.
 *
 * The incantation below is not obvious and is the reason this file exists: the parser does not build a tree,
 * it drives a `SyntaxTreeBuilder`, and the tree is a second pass over the production markers that builder
 * recorded.
 */
object KotlinSyntax {

    /**
     * Parse [text] as a Kotlin file, or as a script when [isScript].
     *
     * Never throws and never returns null, whatever the input. Callers here are editors, looking at code that
     * is wrong by definition halfway through being typed.
     */
    fun parse(text: CharSequence, isScript: Boolean = false): LightSyntaxTree {
        val parser = KotlinParser(isScript = isScript, isLazy = false)
        val builder = SyntaxTreeBuilderFactory.builder(
            text,
            performLexing(text, KotlinLexer(), cancellationProvider = null, logger = null),
            whitespaces = parser.whitespaces,
            comments = parser.comments,
        ).withWhitespaceOrCommentBindingPolicy(parser.whitespaceOrCommentBindingPolicy)
            .build()

        parser.parse(builder)

        return buildLanguageSpecificLightTree(
            builder = builder,
            source = text,
            buildLanguageSpecificTreeStructure = { it },
            isComment = { it in parser.comments },
        )
    }

    /** Parse [text] and return it behind the `Kt*` facade, which is what callers normally want. */
    fun parseFile(text: CharSequence, isScript: Boolean = false): KtFile =
        KtTreeSession(parse(text, isScript)).file
}

/**
 * A one-line rendering of the tree, used by the tests and by the parity oracle: an element as
 * `(TYPE child child)` and a token as its type name.
 *
 * Trivia is excluded by default, because an oracle that compared it would be measuring the whitespace
 * binders rather than the grammar.
 */
fun LightSyntaxTree.render(includeTrivia: Boolean = false): String {
    val out = StringBuilder()
    appendNode(this, getRoot(), out, includeTrivia)
    return out.toString()
}

private fun appendNode(tree: LightSyntaxTree, node: LightNode, out: StringBuilder, includeTrivia: Boolean) {
    val type = tree.getType(node)
    if (tree.isToken(node)) {
        out.append(type.toString())
        return
    }
    out.append('(').append(type.toString())
    for (child in tree.getChildren(node)) {
        if (!includeTrivia && isTriviaName(tree.getType(child).toString())) continue
        out.append(' ')
        appendNode(tree, child, out, includeTrivia)
    }
    out.append(')')
}

private fun isTriviaName(typeName: String): Boolean =
    typeName == "WHITE_SPACE" || typeName.endsWith("COMMENT") || typeName == "KDoc"
