package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.template.SnippetExpansion
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtTypeParameterList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Context-aware Kotlin keyword, modifier and live-template completion. Unlike the symbol candidates (locals,
 * members, types, extensions) these are language tokens, offered ONLY where the grammar admits them so the
 * popup never suggests `for` in an argument slot or `val` at a member-declaration spot that wants `fun`.
 *
 * ## Approach (cf. IntelliJ's `KeywordCompletion`)
 * The Kotlin plugin decides applicability by splicing each candidate keyword into the buffer and re-parsing —
 * letting the parser vote. That works on the desktop parser but on its own it **over-accepts** here: the
 * error-tolerant PSI parser recovers so aggressively that a keyword lands outside any error element even where
 * it is ungrammatical (a member-only modifier at file scope, a declaration keyword after `:` in a type slot,
 * `for`/`while` inside an argument list), because modifier *applicability* and category limits are semantic,
 * not syntactic. IntelliJ layers a large refinement pass over the probe to compensate. We take the refined
 * result directly: a caret [Place] read off the PSI parent chain drives complete, curated keyword sets per
 * position. The sets were derived by probing the real parser (which keywords it admits per reduced context),
 * so they match the grammar without paying a re-parse per keystroke.
 *
 * The caret's [Place]:
 *   • [Place.STATEMENT]           — inside a block body: local declarations + control flow + expression keywords
 *   • [Place.MEMBER]              — inside a class body: member declarations + modifiers
 *   • [Place.TOP_LEVEL]           — file scope: top-level declarations + modifiers
 *   • [Place.EXPRESSION]          — an argument / initializer / default-value / expression-body / when-condition
 *                                   slot: expression keywords only
 *   • [Place.PARAM_PRIMARY_CTOR]  — a primary-constructor parameter: `val`/`var`/`override`/`vararg` + visibility
 *   • [Place.PARAM_SECONDARY_CTOR]— a secondary-constructor parameter: `vararg` only (no property modifiers)
 *   • [Place.PARAM_FUNCTION]      — a function parameter: `vararg` (+ `noinline`/`crossinline` in an `inline` fun)
 *   • [Place.NONE]                — a type, import, package, supertype or lambda-parameter position: nothing
 *
 * A handful of positions the [Place] walk can't classify from ancestry alone (`by` delegation, type-parameter
 * variance, `when`-condition labels, property accessors, `try` continuations, `where` clauses) are detected
 * with dedicated predicates outside the switch.
 *
 * Keywords surface as `kind = KEYWORD`; the multi-line live templates (`if`, `for`, `fun`, `main`, …) surface
 * as `kind = SNIPPET` and step through tab stops on accept via [CaretAction.ExpandSnippet] — the same machinery
 * the postfix templates use. All items are prefix-filtered and rank just below real symbols (the editor's
 * match-tier re-sort still floats an exact keyword match like `if` to the top when the user types it whole).
 */
internal object KotlinKeywords {

    enum class Place {
        TOP_LEVEL, MEMBER, STATEMENT, EXPRESSION,
        PARAM_PRIMARY_CTOR, PARAM_SECONDARY_CTOR, PARAM_FUNCTION, NONE,
    }

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
        val present = precedingModifiers(precedingText, tokenStart)

        val out = ArrayList<CompletionItem>()
        fun kw(text: String, trailingSpace: Boolean = false) {
            val head = text.substringBefore(' ')
            if (head.lowercase() in present) return // already typed (e.g. `private abstract |`)
            if (!text.startsWith(prefix, ignoreCase = true)) return
            out += keywordItem(text, trailingSpace)
        }

        // --- positions the ancestry walk classifies as NONE / STATEMENT / EXPRESSION but which admit their own
        //     keyword, so they're detected explicitly and offered BEFORE the [place] switch ---

        // `by` delegation: a property-delegation spot (`val x █`) reads as STATEMENT/MEMBER, a supertype list
        // (`class A : I █`) reads as [Place.NONE] — both detected from the preceding declaration text.
        val declRun = precedingDeclRun(precedingText, tokenStart)
        if (propertyDelegationSpot(declRun) || classDelegationSpot(declRun)) kw("by", trailingSpace = true)
        // Type-parameter variance/`reified` (`class Box<█>`, `inline fun <█>`) — a [Place.NONE] position.
        typeParamSpot(leaf)?.let { ctx ->
            kw("in", trailingSpace = true); kw("out", trailingSpace = true)
            if (ctx.inlineFun) kw("reified", trailingSpace = true)
        }
        // `where` clause after a generic declaration's signature (`class Box<T> █`, `fun <T> f() █`). Detected
        // from the preceding text like `by`: the half-typed `where` parses as a separate sibling declaration,
        // so it isn't visible in the marker's PSI ancestry.
        if (whereSpot(declRun)) kw("where", trailingSpace = true)
        // `catch`/`finally` after a `try { }` block (or after an existing catch) at statement level.
        if (tryContinuationSpot(leaf)) { kw("catch"); kw("finally") }
        // `get`/`set` property accessors after a property declaration (`val x: Int █`). Only the accessors the
        // property still lacks — a `val` has no setter.
        accessorTarget(leaf)?.let { p ->
            if (p.accessors.none { a -> a.isGetter }) kw("get")
            if (p.isVar && p.accessors.none { a -> a.isSetter }) kw("set")
        }

        // `when`-entry label position (`when (x) { … █ -> … }`). `else` is always valid; the type/range checks
        // (`is`/`in`/`!is`/`!in`) only when the `when` has a subject. A condition is otherwise an expression,
        // so force [Place.EXPRESSION] (a block-scoped `when` would otherwise read as STATEMENT and leak `val`).
        val whenCond = whenConditionSpot(leaf)
        if (whenCond != null) {
            kw("else", trailingSpace = true)
            if (whenCond.hasSubject) { kw("is", trailingSpace = true); kw("in", trailingSpace = true); kw("!is", trailingSpace = true); kw("!in", trailingSpace = true) }
        }

        val place = if (whenCond != null) Place.EXPRESSION else placeOf(leaf)
        if (place == Place.NONE) return out

        // Expression keywords — valid wherever an expression is (statement, argument, initializer, condition).
        if (place == Place.STATEMENT || place == Place.EXPRESSION) {
            for (k in EXPRESSION_KEYWORDS) kw(k)
            kw("throw", trailingSpace = true)
            if (inFunction) kw("return", trailingSpace = true)
        }
        when (place) {
            Place.STATEMENT -> {
                // Local declarations + control flow. No visibility/modality modifiers: local declarations can't
                // carry them, so offering them here would only mislead.
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("for"); kw("while"); kw("do")
                kw("class"); kw("interface"); kw("object"); kw("typealias", trailingSpace = true)
                if (inLoop) { kw("break"); kw("continue") }
            }
            Place.MEMBER -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("init"); kw("constructor"); kw("object"); kw("class"); kw("interface")
                kw("typealias", trailingSpace = true); kw("context")
                kw("override", trailingSpace = true)
                for (c in MEMBER_COMPOUNDS) kw(c)
                for (m in MEMBER_MODIFIERS) kw(m, trailingSpace = true)
            }
            Place.TOP_LEVEL -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("class"); kw("interface"); kw("object"); kw("context")
                kw("typealias", trailingSpace = true); kw("import", trailingSpace = true); kw("package", trailingSpace = true)
                for (c in TOP_LEVEL_COMPOUNDS) kw(c)
                for (m in TOP_LEVEL_MODIFIERS) kw(m, trailingSpace = true)
            }
            // A primary-constructor parameter may be promoted to a property (`val`/`var`), restricted by a
            // visibility modifier, made `vararg`, or `override` an inherited open property — the property stubs
            // for `override` come from KotlinCompletion (it has the supertype resolver); this offers the tokens.
            Place.PARAM_PRIMARY_CTOR -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true)
                kw("override", trailingSpace = true); kw("vararg", trailingSpace = true)
                for (m in PARAM_VISIBILITY) kw(m, trailingSpace = true)
            }
            // A secondary-constructor parameter is a plain function parameter: `vararg` only (it cannot declare a
            // property, so no `val`/`var`/visibility).
            Place.PARAM_SECONDARY_CTOR -> kw("vararg", trailingSpace = true)
            // A function parameter: `vararg`, plus `noinline`/`crossinline` for the function-type parameters of
            // an `inline` function.
            Place.PARAM_FUNCTION -> {
                kw("vararg", trailingSpace = true)
                if (enclosingFunctionIsInline(leaf)) {
                    kw("noinline", trailingSpace = true); kw("crossinline", trailingSpace = true)
                }
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
                // Sign convention (see ItemTierWeigher): positive keeps a not-yet-typed template below real
                // symbols; a fully-typed key (-30) opts into the symbol tier and tops.
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
        // Positive → the engine's tier weigher keeps keywords below real symbols; an exact whole-word match
        // still floats up via the editor's match tier.
        sortPriority = 50,
    )

    // Expression-starting keywords, valid in any expression slot. `object` is an object expression, `if`/`when`/
    // `try` are expressions in Kotlin, `throw`/`return` are handled with tails alongside these.
    private val EXPRESSION_KEYWORDS = listOf("true", "false", "null", "this", "super", "if", "when", "try", "object")

    // --- place detection ---

    /** Classify the grammar position at [leaf] by walking the PSI parent chain to the first decisive node. */
    private fun placeOf(leaf: PsiElement?): Place {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtTypeReference, is KtImportDirective, is KtPackageDirective, is KtSuperTypeList -> return Place.NONE
                is KtValueArgumentList -> return Place.EXPRESSION
                is KtBlockExpression -> return Place.STATEMENT
                is KtClassBody -> return Place.MEMBER
                is KtFile -> return Place.TOP_LEVEL
                // A parameter's default value (`x: Int = |`) is an expression slot, not a modifier spot. (Its
                // type reference is short-circuited to NONE above before the walk reaches here.)
                is KtParameter -> if (prev != null && prev === n.defaultValue) return Place.EXPRESSION
                is KtParameterList -> return paramListPlace(n)
                is KtProperty -> if (prev != null && prev === n.initializer) return Place.EXPRESSION
                is KtNamedFunction -> if (prev != null && prev === n.bodyExpression) return Place.EXPRESSION
            }
            prev = n
            n = n.parent
        }
        return Place.NONE
    }

    /** Which parameter list [list] is, by its owner — primary ctor, secondary ctor, function, or (lambda /
     *  setter / anything else, where no modifier keyword applies) [Place.NONE]. */
    private fun paramListPlace(list: KtParameterList): Place = when (list.parent) {
        is KtPrimaryConstructor -> Place.PARAM_PRIMARY_CTOR
        is KtSecondaryConstructor -> Place.PARAM_SECONDARY_CTOR
        is KtNamedFunction -> Place.PARAM_FUNCTION
        else -> Place.NONE
    }

    /** True when the function enclosing [leaf] is declared `inline` (gating `noinline`/`crossinline`). */
    private fun enclosingFunctionIsInline(leaf: PsiElement?): Boolean {
        var n: PsiElement? = leaf
        while (n != null) {
            if (n is KtNamedFunction) return n.hasModifier(KtTokens.INLINE_KEYWORD)
            n = n.parent
        }
        return false
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
            if (n is KtNamedFunction || n is KtFunctionLiteral || n is KtPropertyAccessor) return true
            n = n.parent
        }
        return false
    }

    /**
     * The whitespace-separated words immediately before [tokenStart] on the current declaration run (back to
     * the previous brace / paren / comma / semicolon / newline). Used to drop a modifier the user already
     * typed. The comma boundary keeps a sibling parameter's modifiers (`class Foo(val x: Int, va|)`) from
     * suppressing `val`/`var` on the parameter being typed.
     */
    private fun precedingModifiers(text: CharSequence, tokenStart: Int): Set<String> {
        var i = (tokenStart - 1).coerceAtMost(text.length - 1)
        val sb = StringBuilder()
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '{' || c == '}' || c == ';' || c == '(' || c == ')' || c == ',') break
            sb.append(c)
            i--
        }
        return sb.reverse().toString().trim()
            .split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase() }.toHashSet()
    }

    private val WHITESPACE = Regex("\\s+")

    // Member-only modifiers. Visibility, modality, and the function/property modifiers that a class member (but
    // not a local declaration) may carry. Class-kind modifiers (`enum`/`data`/`sealed`/`value`/`annotation`)
    // and `companion` come through the compound items so the popup offers the whole `enum class`/`data class`
    // form; `override` is offered separately (it also drives the override-stub completion in KotlinCompletion).
    private val MEMBER_MODIFIERS = listOf(
        "private", "protected", "internal", "public", "abstract", "open", "final",
        "lateinit", "const", "suspend", "inline", "inner", "operator", "infix", "tailrec",
        "external", "expect", "actual",
    )
    private val TOP_LEVEL_MODIFIERS = listOf(
        "private", "internal", "public", "abstract", "open", "final", "sealed", "lateinit", "const",
        "suspend", "inline", "operator", "infix", "tailrec", "external", "expect", "actual",
    )
    private val PARAM_VISIBILITY = listOf("private", "protected", "internal", "public")

    // Compound declaration heads offered as single popup items (the class-kind modifier + its declaration
    // keyword). `data class Foo(...)`, `enum class`, `companion object`, etc. — the common shapes, so the user
    // doesn't complete the modifier and the keyword in two steps.
    private val MEMBER_COMPOUNDS = listOf(
        "companion object", "data class", "enum class", "sealed class", "abstract class", "annotation class",
        "value class", "inner class", "sealed interface", "fun interface", "data object",
    )
    private val TOP_LEVEL_COMPOUNDS = listOf(
        "data class", "enum class", "sealed class", "abstract class", "open class", "annotation class",
        "value class", "sealed interface", "fun interface", "data object",
    )

    // --- `by` delegation (offered independent of [Place]) ---

    // `by` delegation is detected from the preceding declaration TEXT, not the PSI: a half-typed `val x █` /
    // `class A : I █` parses the completion marker into a sibling PsiErrorElement (not inside the property /
    // supertype list), so an ancestry walk can't see them. The text run back to the statement boundary can.
    private fun precedingDeclRun(text: CharSequence, tokenStart: Int): String {
        var i = (tokenStart - 1).coerceAtMost(text.length - 1)
        val sb = StringBuilder()
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '{' || c == '}' || c == ';') break
            sb.append(c); i--
        }
        return sb.reverse().toString().trim()
    }

    /** A property awaiting a delegate/initializer: optional modifiers, `val`/`var`, a name, an optional
     *  `: Type` — and NOTHING after (no `=`/`by` yet). So `val x █` / `val x: Foo █` completes `by`. */
    private val PROPERTY_DELEGATE_RE = Regex("""(?:[a-z]+\s+)*(?:val|var)\s+\w+\s*(?::\s*[^=]+?)?""")
    private fun propertyDelegationSpot(run: String): Boolean = PROPERTY_DELEGATE_RE.matches(run)

    /** A `class`/`object` whose supertype list has been opened (`class A : I █`) but not yet delegated — so
     *  `class A : I by delegate` completes. (Interfaces can't delegate, so they're excluded.) */
    private val CLASS_DELEGATE_RE =
        Regex("""(?:[a-z]+\s+)*(?:class|object)\s+\w+\s*(?:<[^>]*>)?\s*(?:\([^)]*\))?\s*:\s*.+""")
    private fun classDelegationSpot(run: String): Boolean =
        CLASS_DELEGATE_RE.matches(run) && !run.contains(BY_WORD)
    private val BY_WORD = Regex("""\bby\b""")

    private class TypeParamCtx(val inlineFun: Boolean)

    /** Inside a type-parameter list (`class Box<█>`, `fun <█> f()`): offers `in`/`out` variance, plus `reified`
     *  when the owner is an `inline` function. */
    private fun typeParamSpot(leaf: PsiElement?): TypeParamCtx? {
        val list = leaf?.getParentOfType<KtTypeParameterList>(strict = false) ?: return null
        val fn = list.getParentOfType<KtNamedFunction>(strict = true)
        return TypeParamCtx(inlineFun = fn?.hasModifier(KtTokens.INLINE_KEYWORD) == true)
    }

    // --- `when`-entry condition (`else`/`is`/`in`/`!is`/`!in`) ---

    private class WhenCtx(val hasSubject: Boolean)

    /** The [WhenCtx] when [leaf] is at a `when`-entry LABEL position (`when (x) { … █ -> … }`), else null.
     *  Walks up to the first `when` boundary: a [KtWhenEntry] reached from its CONDITION (not its `->`
     *  expression) or the [KtWhenExpression] body directly (a fresh entry being typed) qualifies; a block /
     *  arg-list / class-body in between means we're in an entry's result expression or an inner scope. */
    private fun whenConditionSpot(leaf: PsiElement?): WhenCtx? {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtWhenEntry -> return if (prev == null || prev !== n.expression) whenCtxOf(n.parent) else null
                is KtWhenExpression -> return WhenCtx(hasSubject = n.subjectExpression != null)
                is KtBlockExpression, is KtValueArgumentList, is KtClassBody, is KtFile -> return null
            }
            prev = n
            n = n.parent
        }
        return null
    }

    private fun whenCtxOf(entryParent: PsiElement?): WhenCtx =
        WhenCtx(hasSubject = (entryParent as? KtWhenExpression)?.subjectExpression != null)

    // --- `where` clause, `try` continuations, property accessors ---

    /** A generic `class`/`interface`/`object`/`fun` header whose signature has closed (balanced `<>` and `()`)
     *  and which has no `where` clause yet — so `class Box<T> █` / `fun <T> f(): R █` completes a `where`
     *  constraint. Requires type parameters (a `where` clause has nothing to constrain otherwise). */
    private fun whereSpot(run: String): Boolean {
        if (!WHERE_HEAD_RE.matches(run)) return false
        if (run.contains(WHERE_WORD)) return false
        // The signature must be complete: no dangling `<`/`(` (still inside the type-param list / value params).
        return run.count { it == '<' } == run.count { it == '>' } && run.count { it == '(' } == run.count { it == ')' }
    }

    // A generic declaration header: optional modifiers, then class/interface/object/fun, then anything that
    // includes a `<…>` type-parameter list (no braces — the body hasn't opened).
    private val WHERE_HEAD_RE = Regex("""(?:[a-z]+\s+)*(?:class|interface|object|fun)\b[^{}]*<[^{}]*>[^{}]*""")
    private val WHERE_WORD = Regex("""\bwhere\b""")

    /** True when [leaf] sits right after a `try { … }` (or an existing `catch`/`finally`) block, where a
     *  `catch`/`finally` clause continues the statement. */
    private fun tryContinuationSpot(leaf: PsiElement?): Boolean {
        // The half-typed `catch`/`finally` may attach to the try (a malformed clause) or sit as the next
        // sibling. Cover both: a KtTryExpression ancestor whose try block already closed before the caret, or
        // a preceding sibling that is a KtTryExpression.
        val caret = leaf?.textRange?.startOffset ?: return false
        leaf.getParentOfType<KtTryExpression>(strict = false)?.let { t ->
            val tryBlockEnd = t.tryBlock.textRange.endOffset
            if (caret > tryBlockEnd) return true
        }
        return prevMeaningfulSibling(leaf) is KtTryExpression ||
            prevMeaningfulSiblingUp(leaf) is KtTryExpression
    }

    /** The property [leaf] would give an accessor to (right after a `val`/`var` declaration), or null. `get`/
     *  `set` continues such a property; the caller offers only the accessors it still lacks (`set` needs a
     *  `var`). Either the leaf is inside a KtProperty (a malformed accessor being typed) after its name/type,
     *  or the previous declaration sibling is a KtProperty. */
    private fun accessorTarget(leaf: PsiElement?): KtProperty? {
        val enclosing = leaf?.getParentOfType<KtProperty>(strict = false)
        if (enclosing != null) {
            val caret = leaf.textRange.startOffset
            val nameEnd = (enclosing.typeReference ?: enclosing.nameIdentifier)?.textRange?.endOffset ?: return null
            // After the name/type, not inside the initializer, and no accessors yet.
            return if (caret > nameEnd && enclosing.initializer == null && enclosing.accessors.isEmpty()) enclosing else null
        }
        return (prevMeaningfulSibling(leaf) ?: prevMeaningfulSiblingUp(leaf)) as? KtProperty
    }

    /** The previous sibling of [leaf] skipping whitespace/comments — or null. */
    private fun prevMeaningfulSibling(leaf: PsiElement?): PsiElement? {
        var s = leaf?.prevSibling
        while (s != null && (s is PsiWhiteSpace || s.textLength == 0)) s = s.prevSibling
        return s
    }

    /** The previous meaningful sibling of the nearest ancestor of [leaf] that HAS one — for a caret parsed into
     *  an error/leaf nested below the declaration it follows. */
    private fun prevMeaningfulSiblingUp(leaf: PsiElement?): PsiElement? {
        var n: PsiElement? = leaf
        while (n != null && n !is KtFile) {
            val s = prevMeaningfulSibling(n)
            if (s != null) return s
            n = n.parent
        }
        return null
    }

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
