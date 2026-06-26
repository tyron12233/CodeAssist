package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.template.SnippetExpansion
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Context-aware Kotlin keyword, modifier and live-template completion. Unlike the symbol candidates (locals,
 * members, types, extensions) these are language tokens, offered ONLY where the grammar admits them so the
 * popup never suggests `for` in an argument slot or `val` at a member-declaration spot that wants `fun`.
 *
 * The caret's [Place] is read off the PSI parent chain (mirroring [KotlinCompletion]'s own walks):
 *   • [Place.STATEMENT]  — inside a block body: declarations + control flow + expression keywords
 *   • [Place.MEMBER]     — inside a class body: member declarations + modifiers
 *   • [Place.TOP_LEVEL]  — file scope: top-level declarations + modifiers
 *   • [Place.EXPRESSION] — an argument / initializer / expression-body slot: expression keywords only
 *   • [Place.NONE]       — a type, import, package, parameter or supertype position: nothing
 *
 * Keywords surface as `kind = KEYWORD`; the multi-line live templates (`if`, `for`, `fun`, `main`, …) surface
 * as `kind = SNIPPET` and step through tab stops on accept via [CaretAction.ExpandSnippet] — the same machinery
 * the postfix templates use. All items are prefix-filtered and rank just below real symbols (the editor's
 * match-tier re-sort still floats an exact keyword match like `if` to the top when the user types it whole).
 */
internal object KotlinKeywords {

    enum class Place { TOP_LEVEL, MEMBER, STATEMENT, EXPRESSION, NONE }

    /**
     * Keyword + live-template items for the caret at [leaf]. [precedingText] is the live buffer and
     * [tokenStart] the start of the identifier being typed (so already-typed modifiers are de-duplicated and
     * not re-offered). [inFunction]/[inLoop] gate `return`/`break`/`continue`.
     */
    fun itemsFor(
        leaf: PsiElement?,
        prefix: String,
        precedingText: CharSequence,
        tokenStart: Int,
        inFunction: Boolean,
        inLoop: Boolean,
    ): List<CompletionItem> {
        val place = placeOf(leaf)
        if (place == Place.NONE) return emptyList()
        val present = precedingModifiers(precedingText, tokenStart)

        val out = ArrayList<CompletionItem>()
        fun kw(text: String, trailingSpace: Boolean = false) {
            val head = text.substringBefore(' ')
            if (head.lowercase() in present) return // already typed (e.g. `private abstract |`)
            if (!text.startsWith(prefix, ignoreCase = true)) return
            out += keywordItem(text, trailingSpace)
        }

        // Expression keywords — valid wherever an expression is (statement, argument, initializer).
        if (place == Place.STATEMENT || place == Place.EXPRESSION) {
            kw("true"); kw("false"); kw("null"); kw("this"); kw("super")
            kw("if"); kw("when"); kw("try"); kw("object")
            kw("throw", trailingSpace = true)
            if (inFunction) kw("return", trailingSpace = true)
        }
        when (place) {
            Place.STATEMENT -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true)
                kw("for"); kw("while"); kw("do"); kw("fun", trailingSpace = true)
                if (inLoop) { kw("break"); kw("continue") }
            }
            Place.MEMBER -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("init"); kw("constructor"); kw("companion object"); kw("object")
                kw("class"); kw("interface"); kw("enum class"); kw("override", trailingSpace = true)
                for (m in MEMBER_MODIFIERS) kw(m, trailingSpace = true)
            }
            Place.TOP_LEVEL -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("class"); kw("interface"); kw("object")
                kw("enum class"); kw("sealed class"); kw("data class"); kw("abstract class"); kw("annotation class")
                kw("typealias", trailingSpace = true); kw("import", trailingSpace = true); kw("package", trailingSpace = true)
                for (m in TOP_LEVEL_MODIFIERS) kw(m, trailingSpace = true)
            }
            else -> {}
        }

        // Multi-line live templates for the same place — abbreviations that expand to a full skeleton. Gated
        // to a non-empty prefix so they don't flood the bare-caret popup (cf. lang-jdt's LiveTemplates).
        for (t in TEMPLATES) {
            if (prefix.isEmpty()) break
            if (place !in t.places) continue
            if (!t.key.startsWith(prefix, ignoreCase = true)) continue
            out += CompletionItem(
                label = t.key,
                insertText = t.expansion.text,
                kind = CompletionItemKind.SNIPPET,
                detail = t.preview,
                documentation = t.description,
                sortPriority = if (t.key == prefix) -30 else 70,
                caret = CaretAction.ExpandSnippet(t.expansion),
            )
        }
        return out
    }

    private fun keywordItem(text: String, trailingSpace: Boolean): CompletionItem = CompletionItem(
        label = text,
        insertText = if (trailingSpace) "$text " else text,
        kind = CompletionItemKind.KEYWORD,
        // Below real symbols by default; an exact whole-word match still floats up via the editor's match tier.
        sortPriority = 50,
    )

    // --- place detection ---

    /** Classify the grammar position at [leaf] by walking the PSI parent chain to the first decisive node. */
    private fun placeOf(leaf: PsiElement?): Place {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtTypeReference, is KtImportDirective, is KtPackageDirective,
                is KtParameterList, is KtSuperTypeList -> return Place.NONE
                is KtValueArgumentList -> return Place.EXPRESSION
                is KtBlockExpression -> return Place.STATEMENT
                is KtClassBody -> return Place.MEMBER
                is KtFile -> return Place.TOP_LEVEL
                is KtProperty -> if (prev != null && prev === n.initializer) return Place.EXPRESSION
                is KtNamedFunction -> if (prev != null && prev === n.bodyExpression) return Place.EXPRESSION
            }
            prev = n
            n = n.parent
        }
        return Place.NONE
    }

    /** True when [leaf] sits inside a loop body without crossing a function/lambda boundary (`break`/`continue`). */
    fun isInLoop(leaf: PsiElement?): Boolean {
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtForExpression, is KtWhileExpression, is KtDoWhileExpression -> return true
                is KtNamedFunction, is KtFunctionLiteral -> return false
            }
            n = n.parent
        }
        return false
    }

    /** True when [leaf] is inside a function/lambda/accessor body (where `return` is allowed). */
    fun isInFunction(leaf: PsiElement?): Boolean {
        var n: PsiElement? = leaf
        while (n != null) {
            if (n is KtNamedFunction || n is KtFunctionLiteral) return true
            n = n.parent
        }
        return false
    }

    /**
     * The whitespace-separated words immediately before [tokenStart] on the current declaration run (back to
     * the previous brace / paren / semicolon / newline). Used to drop a modifier the user already typed.
     */
    private fun precedingModifiers(text: CharSequence, tokenStart: Int): Set<String> {
        var i = (tokenStart - 1).coerceAtMost(text.length - 1)
        val sb = StringBuilder()
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '{' || c == '}' || c == ';' || c == '(' || c == ')') break
            sb.append(c)
            i--
        }
        return sb.reverse().toString().trim()
            .split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase() }.toHashSet()
    }

    private val WHITESPACE = Regex("\\s+")

    private val MEMBER_MODIFIERS = listOf(
        "private", "protected", "internal", "public", "abstract", "open", "final",
        "lateinit", "const", "suspend", "inline", "data", "sealed", "inner", "vararg",
    )
    private val TOP_LEVEL_MODIFIERS = listOf(
        "private", "internal", "public", "abstract", "open", "sealed", "data", "suspend", "inline", "const",
    )

    // --- live templates (statement / member / top-level skeletons) ---

    private val ST = setOf(Place.STATEMENT)
    private val ST_EXPR = setOf(Place.STATEMENT, Place.EXPRESSION)
    private val DECL = setOf(Place.STATEMENT, Place.MEMBER, Place.TOP_LEVEL)
    private val TOP = setOf(Place.TOP_LEVEL)

    private class Template(
        val key: String,
        val preview: String,
        val description: String,
        val places: Set<Place>,
        val expansion: SnippetExpansion,
    )

    private val TEMPLATES: List<Template> = listOf(
        Template("main", "fun main() { }", "main entry point", TOP,
            snippet { text("fun main() {\n    "); finalHere(); text("\n}") }),
        Template("maina", "fun main(args: Array<String>) { }", "main with args", TOP,
            snippet { text("fun main(args: Array<String>) {\n    "); finalHere(); text("\n}") }),
        Template("fun", "fun name() { }", "function declaration", DECL,
            snippet { text("fun "); stop(1, "name"); text("() {\n    "); finalHere(); text("\n}") }),
        Template("val", "val name = value", "read-only property", DECL,
            snippet { text("val "); stop(1, "name"); text(" = "); finalHere() }),
        Template("var", "var name = value", "mutable property", DECL,
            snippet { text("var "); stop(1, "name"); text(" = "); finalHere() }),
        Template("if", "if (cond) { }", "if statement", ST_EXPR,
            snippet { text("if ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("ife", "if (cond) { } else { }", "if / else", ST,
            snippet { text("if ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n} else {\n}") }),
        Template("for", "for (item in items) { }", "for-each loop", ST,
            snippet { text("for ("); stop(1, "item"); text(" in "); stop(2, "items"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("forr", "for (i in 0 until n) { }", "indexed for loop", ST,
            snippet { text("for ("); stop(1, "i"); text(" in 0 until "); stop(2, "n"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("while", "while (cond) { }", "while loop", ST,
            snippet { text("while ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("when", "when (subject) { }", "when expression", ST_EXPR,
            snippet { text("when ("); stop(1, "subject"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("try", "try { } catch (e) { }", "try / catch", ST_EXPR,
            snippet { text("try {\n    "); finalHere(); text("\n} catch ("); stop(1, "e"); text(": Exception) {\n}") }),
    )
}
