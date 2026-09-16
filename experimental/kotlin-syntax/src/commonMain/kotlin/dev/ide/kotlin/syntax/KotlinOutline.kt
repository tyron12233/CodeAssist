package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree

/**
 * One declaration in a file's outline.
 *
 * The field names and the [kind] vocabulary are the platform's (`SymbolKind` lowercased), so a host can map
 * this to its own type without deciding anything. That is the only reason this type exists rather than the
 * platform's own: `:kotlin-syntax` has no dependencies, deliberately, and taking one on `:language-api`
 * to borrow an enum would be a poor trade.
 */
data class KotlinOutlineSymbol(
    val name: String,
    val detail: String?,
    val kind: String,
    val nameOffset: Int,
    val endOffset: Int,
    val depth: Int,
)

/** A foldable region. [kind] matches the platform's `FoldKind` ids. */
data class KotlinFold(
    val startOffset: Int,
    val endOffset: Int,
    val placeholder: String,
    val kind: String,
    val collapsedByDefault: Boolean = false,
)

/**
 * The file's outline and its foldable regions, from the parse tree alone.
 *
 * Both need nothing but syntax — no symbols, no classpath, no index — which is what makes them the first
 * Kotlin analysis that can run anywhere the parser does, iOS included.
 *
 * They are computed from a LAZY parse on purpose. Neither answer looks inside a function body, so descending
 * into every one would be paying for depth nobody reads. The one thing that does need a body is folding it,
 * and a collapsed body still knows its own range.
 */
object KotlinOutline {

    /** The declarations in [text], in source order, nested by [KotlinOutlineSymbol.depth]. */
    fun symbols(text: CharSequence): List<KotlinOutlineSymbol> {
        val file = KotlinSyntax.parseFile(text, lazy = true)
        val out = ArrayList<KotlinOutlineSymbol>()
        for (declaration in file.declarations) collect(declaration, depth = 0, out = out)
        return out
    }

    private fun collect(element: KtElement, depth: Int, out: MutableList<KotlinOutlineSymbol>) {
        val symbol = describe(element, depth) ?: return
        out.add(symbol)
        // Only a classifier nests. A function's locals are not outline entries, which is also why the lazy
        // parse costs nothing here.
        val body = (element as? KtClassOrObject)?.body ?: return
        for (member in body.children) collect(member, depth + 1, out)
    }

    private fun describe(element: KtElement, depth: Int): KotlinOutlineSymbol? {
        val name: String
        val kind: String
        val detail: String?
        when (element) {
            is KtClass -> {
                name = element.name ?: return null
                kind = when {
                    element.isInterface -> "interface"
                    element.isEnum -> "enum"
                    element.isAnnotation -> "annotation_type"
                    else -> "class"
                }
                detail = element.primaryConstructor?.valueParameterList?.text
            }

            is KtObjectDeclaration -> {
                // A companion object usually has no name of its own, and "companion" is what a reader expects
                // to see in an outline rather than a blank row.
                name = element.name ?: if (element.isCompanion) "companion" else return null
                kind = "class"
                detail = null
            }

            is KtEnumEntry -> {
                name = element.name ?: return null
                kind = "enum_constant"
                detail = null
            }

            is KtNamedFunction -> {
                name = element.name ?: return null
                kind = "method"
                detail = element.valueParameterList?.text
            }

            is KtSecondaryConstructor -> {
                name = "constructor"
                kind = "constructor"
                detail = element.valueParameterList?.text
            }

            is KtProperty -> {
                name = element.name ?: return null
                kind = "field"
                detail = element.typeReference?.text
            }

            is KtTypeAlias -> {
                name = element.name ?: return null
                kind = "class"
                detail = element.typeReference?.text
            }

            else -> return null
        }
        return KotlinOutlineSymbol(
            name = name,
            detail = detail,
            kind = kind,
            // The NAME's offset, not the declaration's: this is where navigation puts the caret, and what a
            // sticky header pins. Falling back to the declaration start keeps an unnamed one navigable.
            nameOffset = element.nameIdentifierOffset() ?: element.textOffset,
            endOffset = element.textOffset + element.textLength,
            depth = depth,
        )
    }

    private fun KtElement.nameIdentifierOffset(): Int? =
        children.firstOrNull { it.elementType == KtTokens.IDENTIFIER }?.textOffset

    /**
     * Foldable regions in [text].
     *
     * Single-line regions are left out: folding something that is already one line is a control that does
     * nothing, and an outline full of them is worse than none.
     */
    fun folds(text: CharSequence): List<KotlinFold> {
        val tree = KotlinSyntax.parse(text, lazy = true)
        val out = ArrayList<KotlinFold>()
        walk(tree, tree.getRoot(), text, out)
        return out.filter { text.hasLineBreakIn(it.startOffset, it.endOffset) }
    }

    private fun walk(tree: LightSyntaxTree, node: LightNode, text: CharSequence, out: MutableList<KotlinFold>) {
        val start = tree.getStartOffset(node)
        val end = tree.getEndOffset(node)
        when (tree.getType(node)) {
            KtNodeTypes.IMPORT_LIST -> if (end > start) {
                out.add(KotlinFold(start, end, "...", "imports", collapsedByDefault = true))
            }

            KtNodeTypes.CLASS_BODY -> out.add(KotlinFold(start, end, "{...}", "classBody"))

            KtNodeTypes.BLOCK -> out.add(KotlinFold(start, end, "{...}", "functionBody"))

            KtNodeTypes.STRING_TEMPLATE -> out.add(KotlinFold(start, end, "\"...\"", "string"))
        }
        for (child in tree.getChildren(node)) {
            if (tree.getType(child) in KtTokens.COMMENTS) {
                out.add(
                    KotlinFold(
                        tree.getStartOffset(child),
                        tree.getEndOffset(child),
                        "/.../",
                        "comment",
                    ),
                )
            }
            walk(tree, child, text, out)
        }
    }

    private fun CharSequence.hasLineBreakIn(start: Int, end: Int): Boolean {
        for (i in start until minOf(end, length)) if (this[i] == '\n') return true
        return false
    }
}
