package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtConstructorCalleeExpression
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtOperationReferenceExpression
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtSuperTypeList
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeParameterList
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.template.SnippetExpansion
import dev.ide.lang.template.indented
import dev.ide.lang.template.lineIndentAt

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
 *   • [Place.PARAM_PRIMARY_CTOR]  : a primary-constructor parameter: the function parameter's set, plus
 *                                   `val`/`var`/`override`, visibility and the member-property modifiers
 *   • [Place.PARAM_SECONDARY_CTOR]: a secondary-constructor parameter: `vararg`/`noinline`/`crossinline`/`context`
 *   • [Place.PARAM_FUNCTION]      : a function parameter: the same as a secondary constructor's
 *   • [Place.NONE]                — a type, import, package, supertype or lambda-parameter position: nothing
 *
 * A handful of positions the [Place] walk can't classify from ancestry alone (`by` delegation, type-parameter
 * variance, `when`-condition labels, property accessors, `try` continuations, `where` clauses) are detected
 * with dedicated predicates outside the switch. Three of those are *exclusive* — the only keyword the slot
 * admits is the position's own, so they're offered and returned before the [place] switch (whose enclosing-
 * block walk would otherwise leak `val`/`do`/`if` into the slot): an import alias (`import a.B █` → `as`),
 * an infix-operator name (`0 downTo█`, `x as█`, `x in█` — the operation reference of a binary expression),
 * and a for-loop's `in` (`for (x █`).
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
        leaf: KtElement?,
        prefix: String,
        precedingText: CharSequence,
        tokenStart: Int,
        inFunction: Boolean,
        inLoop: Boolean,
        /** Whether a class is on this module's classpath: the Android and Compose templates need theirs. */
        hasType: (String) -> Boolean = { false },
    ): List<CompletionItem> {
        val declRun = precedingDeclRun(precedingText, tokenStart)
        // Right after an import on its line (`import a.b.C █`): its words are no modifiers already typed.
        val afterImport = importAliasSpot(declRun)
        val present = if (afterImport) emptySet() else precedingModifiers(precedingText, tokenStart)

        val out = ArrayList<CompletionItem>()
        fun kw(text: String, trailingSpace: Boolean = false) {
            val head = text.substringBefore(' ')
            if (head.lowercase() in present) return // already typed (e.g. `private abstract |`)
            // Case-sensitive, as IntelliJ matches keywords: every keyword is lowercase, so a capitalized prefix
            // (`P|`) is a type being named and offers none.
            if (!text.startsWith(prefix)) return
            out += keywordItem(text, trailingSpace)
        }

        // `context`, and the context-parameter list it starts written out (`context(|)`), at a declaration.
        fun contextList() {
            kw("context")
            if ("context".startsWith(prefix) && "context" !in present) {
                out += keywordItem("context", trailingSpace = false).copy(insertText = "context()", detail = "(...)", caret = CaretAction.At(8))
            }
        }

        // The compounds that are shortcuts for a modifier plus a declaration keyword (`value class`, `fun
        // interface`) are offered once something is typed: the bare popup lists the plain `value` modifier and
        // `fun`/`interface`, the vocabulary IntelliJ shows there, rather than every pairing of them.
        // What already follows the word being typed (`seal|class A`, `seal| fun interface A`): a declaration
        // keyword there takes the modifier ALONE (`sealed`), and a modifier there (`seal| sealed class`) takes no
        // compound at all, as IntelliJ completes them.
        val following = followingWords(precedingText, tokenStart + prefix.length)
        fun compound(text: String) {
            if (text in TYPED_ONLY_COMPOUNDS && prefix.isEmpty()) return
            if (following.isNotEmpty()) {
                val head = text.substringBefore(' ')
                val declared = following.removePrefix("fun ").substringBefore(' ')
                if (following.startsWith(head)) return
                if (declared == text.substringAfter(' ') && head !in present && out.none { it.label == head }) kw(head, trailingSpace = true)
                return
            }
            kw(text)
        }

        // --- positions the ancestry walk classifies as NONE / STATEMENT / EXPRESSION but which admit their own
        //     keyword, so they're detected explicitly and offered BEFORE the [place] switch ---

        // Right after an annotation's `@` (`@<caret>`, `class C(@<caret>)`): the use-site targets (`file:`,
        // `get:`, `param:`, …), the only keywords an annotation's name may start with.
        if (annotationStartSpot(precedingText, tokenStart)) {
            for (t in USE_SITE_TARGETS) kw("$t:")
            return out
        }
        // After a `!` that follows an operand (`v !<caret>`): the negated `in`/`is` operators.
        if (negatedOperatorSpot(precedingText, tokenStart)) {
            kw("in", trailingSpace = true); kw("is", trailingSpace = true)
            return out
        }

        // Exclusive positions — the slot admits ONLY the position's own keyword(s), so they're offered and
        // returned before the [place] switch (whose enclosing-block walk would otherwise leak `do`/`if`/`val`
        // into the slot alongside the prefix-matching intent).
        //
        //   `import a.b.C █`  → `as` (aliased import). The half-typed alias parses as a PsiErrorElement sibling
        //                        of the import directive, so it's read from the preceding text like `by`.
        //                        What may follow the import line comes along (IntelliJ's AfterImportDirective).
        if (afterImport) kw("as", trailingSpace = true)
        //   `for (x █`         → `in`, before the loop range's `in` keyword has been typed.
        if (forLoopInSpot(leaf)) { kw("in", trailingSpace = true); return out }
        //   `constructor() : █` → `this` / `super`, a secondary constructor's delegation call (the only two
        //                          things that fit the delegation-reference slot).
        if (constructorDelegationSpot(leaf, declRun)) { kw("this"); kw("super"); return out }

        // `by` delegation: a property-delegation spot (`val x █`) reads as STATEMENT/MEMBER, a supertype list
        // (`class A : I █`) reads as [Place.NONE] — both detected from the preceding declaration text.
        if (propertyDelegationSpot(declRun) || classDelegationSpot(declRun)) kw("by", trailingSpace = true)
        // Type-parameter variance/`reified` (`class Box<█>`, `inline fun <█>`) — a [Place.NONE] position.
        typeParamSpot(leaf)?.let { ctx ->
            kw("in", trailingSpace = true); kw("out", trailingSpace = true)
            if (ctx.inlineFun || ctx.ofClass) kw("reified", trailingSpace = true)
            // A class's type parameter being written (not its bound) admits nothing else; IntelliJ also lists
            // `reified` (the compiler rejects it there) and `context`.
            if (ctx.ofClass && ctx.atName) { kw("context"); return out }
        }
        // `where` clause after a generic declaration's signature (`class Box<T> █`, `fun <T> f() █`). Detected
        // from the preceding text like `by`: the half-typed `where` parses as a separate sibling declaration,
        // so it isn't visible in the marker's PSI ancestry.
        if (whereSpot(declRun)) kw("where", trailingSpace = true)
        // An import on the line before may still take its alias (`import a.B` / `as C`).
        if (importOnLineBefore(precedingText, tokenStart)) kw("as", trailingSpace = true)
        // A class's name written and nothing after it yet (`class Foo █ {`): its primary constructor may be
        // spelled out (`class Foo constructor(…)`, `class Foo private constructor(…)`).
        if ("class" in declRun && CLASS_HEADER_RE.matches(declRun)) kw("constructor")
        // `catch`/`finally` after a `try { }` block (or after an existing catch) at statement level.
        val afterTry = tryContinuationSpot(leaf)
        if (afterTry) { kw("catch"); kw("finally") }
        // `else` after an `if (…) { }` that has no else yet; `while` after a `do { }` missing its while.
        val afterIf = elseContinuationSpot(leaf)
        if (afterIf) kw("else", trailingSpace = true)
        // The `if`/`try` just closed is an expression the next line may still cast (`if (a) {} as T`), so the
        // cast operators fit there too, as IntelliJ's keyword completion has them after either.
        if ((afterTry || afterIf) && leaf?.getParentOfType<KtBlockExpression>(strict = false) != null) {
            kw("as", trailingSpace = true); kw("as?", trailingSpace = true)
        }
        if (doWhileContinuationSpot(leaf)) kw("while")
        // `get`/`set` property accessors after a property declaration (`val x: Int █`). Only the accessors the
        // property still lacks — a `val` has no setter.
        accessorTarget(leaf)?.takeUnless { it.isLocal }?.let { p ->
            fun accessor(head: String, parameters: String) {
                kw(head)
                if (!head.startsWith(prefix)) return
                // The accessor written out, as an expression body or a block body.
                out += keywordItem("$head$parameters = ...", trailingSpace = false).copy(insertText = "$head$parameters = ")
                out += keywordItem("$head$parameters {...}", trailingSpace = false)
                    .copy(insertText = "$head$parameters {  }", caret = CaretAction.At(head.length + parameters.length + 3))
            }
            if (p.accessors.none { a -> a.isGetter }) accessor("get", "()")
            if (p.isVar && p.accessors.none { a -> a.isSetter }) accessor("set", "(value)")
            // An explicit backing field (`field = …`) may follow the property too.
            kw("field", trailingSpace = true)
            // A property with neither an initializer nor accessors may still be delegated on the next line.
            if (p.initializer == null && !p.hasDelegate() && p.accessors.isEmpty() && out.none { it.label == "by" }) kw("by", trailingSpace = true)
            // A property that ends in an expression (`= 1`, `get() = 0`) may still have it cast.
            val last = p.accessors.lastOrNull()
            if (if (last != null) last.equalsToken != null else p.initializer != null) {
                kw("as", trailingSpace = true); kw("as?", trailingSpace = true)
            }
            // On the property's own line only its continuations fit: no declaration starts there.
            val end = p.textRange.endOffset.coerceAtMost(tokenStart)
            if (end <= tokenStart && (end until tokenStart).none { precedingText[it] == '\n' || precedingText[it] == ';' }) return out
        }

        // `when`-entry label position (`when (x) { … █ -> … }`). `else` is always valid; the type/range checks
        // (`is`/`in`/`!is`/`!in`) only when the `when` has a subject. A condition is otherwise an expression,
        // so force [Place.EXPRESSION] (a block-scoped `when` would otherwise read as STATEMENT and leak `val`).
        val whenCond = whenConditionSpot(leaf)
        if (whenCond != null) {
            kw("else", trailingSpace = true)
            if (whenCond.hasSubject) { kw("is", trailingSpace = true); kw("in", trailingSpace = true); kw("!is", trailingSpace = true); kw("!in", trailingSpace = true) }
        }

        // Infix-operator slot (`x as█`, `0 downTo█`) — the `as`/`as?`/`in`/`!in`/`is`/`!is` KEYWORD operators;
        // the infix FUNCTIONS (`downTo`/`step`/user-defined) are type-resolved against the left operand by
        // KotlinCompletion, not hardcoded here. Checked LAST and only when no continuation predicate above
        // claimed the slot: a `try { } catch█` / `val x: Int get█` also parses as a binary operation reference,
        // but its catch / accessor reading (already in [out]) must win.
        if (out.isEmpty() && infixOperatorSpot(leaf)) {
            for (op in INFIX_OPERATORS) kw(op, trailingSpace = true)
            return out
        }

        // A type (`val a: █`, `fun f(p: █)`, `List<█>`) may start with `suspend` (a suspend function type) or
        // `context(…)` (a function type with context parameters), and a type argument of another type with its
        // variance (`in`/`out`), as IntelliJ offers them in any type position but a receiver or a supertype call.
        typeReferenceSpot(leaf)?.let { argumentOfType ->
            if (argumentOfType) { kw("in", trailingSpace = true); kw("out", trailingSpace = true) }
            kw("suspend", trailingSpace = true); kw("context")
            return out
        }

        val whenSubject = whenSubjectSpot(leaf)
        // A `when` subject may declare its value (`when (val v = …)`), never a `var` (IntelliJ's AllowValInWhen).
        if (whenSubject) kw("val", trailingSpace = true)
        val place = if (afterImport) Place.TOP_LEVEL else if (whenCond != null || whenSubject) Place.EXPRESSION else placeOf(leaf)
        if (place == Place.NONE) return out

        // Expression keywords — valid wherever an expression is (statement, argument, initializer, condition).
        if (place == Place.STATEMENT || place == Place.EXPRESSION) {
            for (k in EXPRESSION_KEYWORDS) kw(k)
            kw("throw", trailingSpace = true)
            if (inFunction) kw("return", trailingSpace = true)
            // An expression slot also gets the loops and `fun` (an anonymous function), as IntelliJ's K2 keyword
            // set has them wherever an expression goes; a statement gets them below with the declarations.
            if (place == Place.EXPRESSION) { kw("do"); kw("for"); kw("while"); kw("fun", trailingSpace = true) }
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
                // An `object` (a companion too) has no constructor, no companion and no inner class, and
                // nothing in it is `protected`; it is where a `const val` may be declared.
                val inObject = leaf?.getParentOfType<KtClassOrObject>(strict = true) is KtObjectDeclaration
                kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                kw("init"); kw("object"); kw("class"); kw("interface")
                kw("typealias", trailingSpace = true); contextList()
                kw("override", trailingSpace = true)
                if (!inObject) { kw("constructor"); kw("companion object") }
                for (c in MEMBER_COMPOUNDS) compound(c)
                // `const val` only in an object declaration: an object EXPRESSION's properties cannot be const.
                if (inObject && (leaf?.getParentOfType<KtClassOrObject>(strict = true) as? KtObjectDeclaration)?.isObjectLiteral() != true) {
                    kw("const val", trailingSpace = true)
                }
                for (m in MEMBER_MODIFIERS) {
                    if (inObject && (m == "protected" || m == "inner")) continue
                    kw(m, trailingSpace = true)
                }
            }
            Place.TOP_LEVEL -> {
                // A file is `package`, then `import`s, then declarations, in that order: before an import only
                // `package`/`import` fit, and once a declaration is written neither does.
                val file = leaf?.getParentOfType<KtFile>(strict = false)
                // An import further down may not parse as one, the caret's word between them having broken the
                // import list, so the text is asked too.
                val importAfter = file?.importDirectives?.any { it.textRange.startOffset >= tokenStart } == true ||
                    IMPORT_LINE.containsMatchIn(precedingText.subSequence((tokenStart + prefix.length).coerceAtMost(precedingText.length), precedingText.length))
                val importBefore = file?.importDirectives?.any { it.textRange.endOffset <= tokenStart } == true
                val declarationBefore = file?.declarations?.any { it.textRange.endOffset <= tokenStart } == true
                val packageWritten = file?.packageDirective?.let { it.textLength > 0 && it.textRange.endOffset <= tokenStart } == true
                if (!declarationBefore || afterImport) kw("import", trailingSpace = true)
                if (!declarationBefore && !importBefore && !packageWritten) kw("package", trailingSpace = true)
                if (!importAfter) {
                    kw("val", trailingSpace = true); kw("var", trailingSpace = true); kw("fun", trailingSpace = true)
                    kw("class"); kw("interface"); kw("object"); contextList()
                    kw("typealias", trailingSpace = true)
                    for (c in TOP_LEVEL_COMPOUNDS) compound(c)
                    for (m in TOP_LEVEL_MODIFIERS) kw(m, trailingSpace = true)
                    // The class a file is named for, when it does not declare it yet (`class Foo` in `Foo.kt`).
                    fileClassName(file)?.let { name ->
                        for (head in FILE_CLASS_HEADS) {
                            if (out.none { it.kind == CompletionItemKind.KEYWORD && it.label == head }) continue
                            out += if (head == "data class") CompletionItem(
                                label = head,
                                insertText = "$head $name()",
                                kind = CompletionItemKind.KEYWORD,
                                detail = "$name(...)",
                                sortPriority = 50,
                                caret = CaretAction.At(head.length + name.length + 2),
                            ) else keywordItem("$head $name", trailingSpace = false)
                        }
                    }
                }
            }
            // A primary-constructor parameter may be promoted to a property (`val`/`var`), restricted by a
            // visibility modifier, made `vararg`, or `override` an inherited open property — the property stubs
            // for `override` come from KotlinCompletion (it has the supertype resolver); this offers the tokens.
            // IntelliJ's keyword completion admits a modifier wherever one of its targets is a VALUE_PARAMETER
            // (and, in a primary constructor, a MEMBER_PROPERTY too), without asking whether the function is
            // `inline`: so `noinline`/`crossinline` everywhere, and the property modifiers in a primary
            // constructor, the plugin's own test data noting that some of them do not compile there.
            Place.PARAM_PRIMARY_CTOR -> {
                kw("val", trailingSpace = true); kw("var", trailingSpace = true)
                kw("override", trailingSpace = true); kw("vararg", trailingSpace = true)
                kw("noinline", trailingSpace = true); kw("crossinline", trailingSpace = true)
                for (m in PARAM_VISIBILITY) kw(m, trailingSpace = true)
                for (m in PRIMARY_CTOR_PROPERTY_MODIFIERS) kw(m, trailingSpace = true)
                kw("context")
            }
            // A secondary-constructor or function parameter cannot declare a property, so no `val`/`var`/visibility.
            Place.PARAM_SECONDARY_CTOR, Place.PARAM_FUNCTION -> {
                kw("vararg", trailingSpace = true)
                kw("noinline", trailingSpace = true); kw("crossinline", trailingSpace = true)
                kw("context")
            }
            else -> {}
        }

        // Multi-line live templates for the same place — abbreviations that expand to a full skeleton. Gated
        // to a non-empty prefix so they don't flood the bare-caret popup (cf. lang-jdt's LiveTemplates), to the
        // libraries they use (the Android and Compose ones), and re-indented to the line they land on.
        if (prefix.isNotEmpty()) {
            val env = TemplateEnv(leaf, place)
            val indent = lineIndentAt(precedingText, tokenStart)
            for (t in TEMPLATES) {
                if (place !in t.places) continue
                if (!t.key.startsWith(prefix, ignoreCase = true)) continue
                if (!t.requires.all(hasType)) continue
                val expansion = t.build(env)?.indented(indent) ?: continue
                out += CompletionItem(
                    label = t.key,
                    insertText = expansion.text,
                    kind = CompletionItemKind.SNIPPET,
                    detail = t.preview,
                    documentation = t.description,
                    // Sign convention (see ItemTierWeigher): positive keeps a not-yet-typed template below real
                    // symbols; a fully-typed key (-30) opts into the symbol tier and tops.
                    sortPriority = if (t.key == prefix) -30 else 70,
                    additionalEdits = t.imports.mapNotNull { kotlinImportEdit(precedingText, it) },
                    caret = CaretAction.ExpandSnippet(expansion),
                )
            }
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

    /** The next two words from [from] on (spaces skipped), or "" when the next thing is not a word. */
    private fun followingWords(text: CharSequence, from: Int): String {
        // A word glued to the caret (`seal|class A`) is the next word too: the caret's token ends at the caret.
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        val start = i
        var words = 0
        while (i < text.length && words < 2) {
            if (text[i].isLetter()) {
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                words++
                if (words < 2) while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            } else break
        }
        return text.substring(start, i).trim()
    }

    // --- place detection ---

    /** Classify the grammar position at [leaf] by walking the PSI parent chain to the first decisive node. */
    private fun placeOf(leaf: KtElement?): Place {
        var prev: KtElement? = null
        var n: KtElement? = leaf
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
                // A getter's or setter's expression body (`get() = |`).
                is KtPropertyAccessor -> if (prev != null && prev === n.bodyExpression) return Place.EXPRESSION
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

    /** True when [leaf] sits inside a loop body without crossing a function/lambda boundary (`break`/`continue`). */
    fun isInLoop(leaf: KtElement?): Boolean {
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtForExpression, is KtWhileExpression, is KtDoWhileExpression -> return true
                is KtNamedFunction, is KtFunctionLiteral -> return false
            }
            n = n.parent
        }
        return false
    }

    /**
     * `interface` at the name of a top-level or member `fun` being declared (`fun i|`), the rest of a `fun
     * interface`, as IntelliJ's keyword completion has it there. Null anywhere else, or when [prefix] does not
     * start it.
     */
    fun funInterfaceItem(leaf: KtElement?, prefix: String): CompletionItem? {
        val fn = leaf?.parent as? KtNamedFunction ?: return null
        if (fn.nameIdentifier !== leaf || fn.receiverTypeReference != null || fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
        if (fn.parent !is KtFile && fn.parent !is KtClassBody) return null
        if (!"interface".startsWith(prefix)) return null
        return keywordItem("interface", trailingSpace = true)
    }

    /** True when [leaf] is inside a function/lambda/accessor body (where `return` is allowed). */
    fun isInFunction(leaf: KtElement?): Boolean {
        var n: KtElement? = leaf
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
    // The vocabulary IntelliJ's Kotlin plugin offers at a declaration start: a modifier that only ever heads
    // one kind of declaration is offered WITH it (`lateinit var`, `const val`, `suspend fun`, below), not alone.
    private val MEMBER_MODIFIERS = listOf(
        "private", "protected", "internal", "public", "abstract", "open", "final",
        "inline", "value", "inner", "operator", "infix", "tailrec",
        "external", "expect", "actual",
    )
    private val TOP_LEVEL_MODIFIERS = listOf(
        "private", "internal", "public", "abstract", "open", "final",
        "inline", "value", "operator", "infix", "tailrec", "external", "expect", "actual",
    )
    private val PARAM_VISIBILITY = listOf("private", "protected", "internal", "public")

    /** The modifiers of a member property a primary-constructor parameter also takes as IntelliJ offers them. */
    private val PRIMARY_CTOR_PROPERTY_MODIFIERS = listOf("open", "final", "abstract", "actual", "lateinit var")

    // Compound declaration heads offered as single popup items (the class-kind modifier + its declaration
    // keyword). `data class Foo(...)`, `enum class`, `companion object`, etc. — the common shapes, so the user
    // doesn't complete the modifier and the keyword in two steps.
    // `value class` and `fun interface` go beyond IntelliJ's list (it offers `value` alone, and `interface`
    // after `fun`), kept because each is the one declaration its first word starts.
    private val MEMBER_COMPOUNDS = listOf(
        "data class", "enum class", "sealed class", "annotation class", "sealed interface", "data object",
        "lateinit var", "suspend fun", "value class", "fun interface",
    )
    private val TYPED_ONLY_COMPOUNDS = setOf("value class", "fun interface")
    private val TOP_LEVEL_COMPOUNDS = listOf(
        "data class", "enum class", "sealed class", "annotation class", "sealed interface", "data object",
        "lateinit var", "const val", "suspend fun", "value class", "fun interface",
    )

    /** A line that is an import directive. */
    private val IMPORT_LINE = Regex("""(?m)^[ \t]*import\s""")

    /** Whether the last line written before [tokenStart] (past blank ones) is an import directive that is
     *  neither aliased nor a star import, and the caret is on a later line. Reads back from the caret only. */
    private fun importOnLineBefore(text: CharSequence, tokenStart: Int): Boolean {
        var end = tokenStart.coerceAtMost(text.length)
        var sawBreak = false
        while (end > 0 && text[end - 1].isWhitespace()) { if (text[end - 1] == '\n') sawBreak = true; end-- }
        if (!sawBreak || end == 0) return false
        var start = end
        while (start > 0 && text[start - 1] != '\n') start--
        return IMPORT_DIRECTIVE.matches(text.subSequence(start, end))
    }
    private val IMPORT_DIRECTIVE = Regex("""[ \t]*import[ \t]+[\w.`]+""")

    /** The declaration heads offered with the file's own name too (see [fileClassName]). */
    private val FILE_CLASS_HEADS = listOf(
        "class", "interface", "object", "enum class", "sealed class", "sealed interface", "annotation class",
        "data class", "data object",
    )

    /** The name of [file] when it can name a class the file does not declare yet: a capitalized identifier. */
    private fun fileClassName(file: KtFile?): String? {
        val name = file?.name?.substringAfterLast('/')?.substringBeforeLast('.') ?: return null
        if (name.isEmpty() || !name[0].isUpperCase() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        if (file.declarations.any { it is KtClassOrObject && it.name == name }) return null
        return name
    }

    /** The annotation use-site targets, in IntelliJ's order. */
    private val USE_SITE_TARGETS = listOf(
        "file", "field", "get", "set", "param", "setparam", "property", "receiver", "delegate", "all",
    )

    /** Whether the token at [tokenStart] directly follows an annotation's `@`: not a label's (`loop@`,
     *  `return@`), whose `@` is glued to a name. */
    private fun annotationStartSpot(text: CharSequence, tokenStart: Int): Boolean {
        if (text.getOrNull(tokenStart - 1) != '@') return false
        val before = text.getOrNull(tokenStart - 2) ?: return true
        return !(before.isLetterOrDigit() || before == '_' || before == '`')
    }

    /** Whether the token at [tokenStart] follows a `!` written after an operand (`v !<caret>`, `it !i<caret>`),
     *  where only `!in`/`!is` continue: a `!` that starts an operand (`!flag`, `(!x`) does not count. */
    private fun negatedOperatorSpot(text: CharSequence, tokenStart: Int): Boolean {
        if (text.getOrNull(tokenStart - 1) != '!') return false
        var i = tokenStart - 2
        if (i < 0 || !text[i].isWhitespace()) return false
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        val c = text.getOrNull(i) ?: return false
        return c.isLetterOrDigit() || c == '_' || c == ')' || c == ']' || c == '"'
    }

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

    // --- import alias, infix operators, for-loop `in` (the exclusive positions offered before the switch) ---

    /** An `import a.b.C █` whose imported path is complete and not yet aliased — so `as` completes the alias.
     *  Detected from the preceding text (like `by`): the half-typed alias parses as a sibling PsiErrorElement,
     *  not into the import directive. A star import (`import a.*`) can't be aliased, so `*` fails the match. */
    private val IMPORT_ALIAS_RE = Regex("""import\s+[\w.]+""")

    /** A class header up to its name and type parameters: `class Foo`, `data class Box<T>`. */
    private val CLASS_HEADER_RE = Regex("""(?:[a-z]+\s+)*class\s+\w+\s*(?:<[^{}()]*>)?""")
    private fun importAliasSpot(run: String): Boolean = IMPORT_ALIAS_RE.matches(run)

    /** True when [leaf] is the operation-reference slot of a binary expression (`a foo█`, `0 downTo█`, `x as█`)
     *  — where an infix function name or the `as`/`in`/`is` operators complete. A right operand (`a + b█`) is a
     *  plain name reference, not the operation reference, so it doesn't qualify. */
    private fun infixOperatorSpot(leaf: KtElement?): Boolean {
        val ref = leaf?.getParentOfType<KtOperationReferenceExpression>(strict = false) ?: return false
        return (ref.parent as? KtBinaryExpression)?.operationReference === ref
    }

    /** The hard-keyword operators admissible at an [infixOperatorSpot] (`x as T`, `x in xs`, `x is T`). These
     *  are language tokens, not functions — the infix *functions* that also fit the slot are resolved against
     *  the left operand's type by KotlinCompletion so a user-defined `infix fun` completes too. */
    private val INFIX_OPERATORS = listOf("in", "!in", "is", "!is", "as", "as?")

    /** True when [leaf] sits after a for-loop's variable but before its range's `in` (`for (x █`), so `in`
     *  completes the loop header. Excludes still-typing-the-variable (`for (fo█`, marker inside the parameter)
     *  and a loop whose range/`in` is already present (`for (x in █`, `loopRange` non-null). */
    private fun forLoopInSpot(leaf: KtElement?): Boolean {
        val forE = leaf?.getParentOfType<KtForExpression>(strict = false) ?: return false
        if (forE.loopRange != null) return false
        val param = forE.loopParameter ?: return false
        return leaf.textRange.startOffset > param.textRange.endOffset
    }

    /**
     * True at the delegation-reference slot of a SECONDARY constructor (`constructor(…) : █`, before the
     * `this(…)`/`super(…)` call) — the only position where the `this`/`super` delegation keywords fit.
     * Detected from the preceding TEXT ([run]) like `by`/`where`: the half-typed delegation reference parses
     * as a `PsiErrorElement` sibling under the class body, not into a `KtSecondaryConstructor`, so an ancestry
     * walk can't see it. Requiring a [KtClassBody] ancestor excludes a PRIMARY constructor's `: Base()`
     * supertype list, which sits in the class HEADER (before `{`); a fully-typed `this(█)`/`super(█)` argument
     * slot doesn't match the regex (the `(` follows the `:`), so those stay ordinary expression positions.
     */
    private fun constructorDelegationSpot(leaf: KtElement?, run: String): Boolean =
        CTOR_DELEGATION_RE.matches(run) && leaf?.getParentOfType<KtClassBody>(strict = false) != null

    /** A secondary constructor whose delegation `:` has been opened but not yet delegated (`constructor(…) : `),
     *  optionally preceded by visibility modifiers. Anchored to the literal `constructor` keyword. */
    private val CTOR_DELEGATION_RE = Regex("""(?:[a-z]+\s+)*constructor\s*\([^)]*\)\s*:\s*""")

    private class TypeParamCtx(val inlineFun: Boolean, val ofClass: Boolean, val atName: Boolean)

    /** Inside a type-parameter list (`class Box<█>`, `fun <█> f()`): offers `in`/`out` variance, plus `reified`
     *  when the owner is an `inline` function or a class (as IntelliJ offers it). */
    private fun typeParamSpot(leaf: KtElement?): TypeParamCtx? {
        val list = leaf?.getParentOfType<KtTypeParameterList>(strict = false) ?: return null
        val fn = list.getParentOfType<KtNamedFunction>(strict = true)
        val inBound = generateSequence(leaf as KtElement?) { it.parent }.takeWhile { it !== list }.any { it is KtTypeReference }
        return TypeParamCtx(
            inlineFun = fn?.hasModifier(KtTokens.INLINE_KEYWORD) == true,
            ofClass = list.parent is KtClassOrObject,
            atName = !inBound,
        )
    }

    /**
     * Whether [leaf] is the name of an unqualified type in a type reference that admits a type-starting keyword:
     * null when it is not (no type reference, a qualified `a.B`, an extension receiver `fun A.f()`, or the type
     * of a supertype or annotation call `A()`); otherwise whether the type is an argument of another type
     * (`List<█>`), where a variance projection also fits. A call's type arguments (`listOf<█>()`) take no
     * projection.
     */
    private fun typeReferenceSpot(leaf: KtElement?): Boolean? {
        val ref = leaf?.getParentOfType<KtTypeReference>(strict = false) ?: return null
        val type = ref.typeElement as? KtUserType ?: return null
        if (type.qualifier != null || leaf.getParentOfType<KtUserType>(strict = false) !== type) return null
        val parent = ref.parent
        if (parent is KtCallableDeclaration && parent.receiverTypeReference === ref) return null
        if (parent is KtConstructorCalleeExpression) return null
        val arguments = (parent as? KtTypeProjection)?.parent as? KtTypeArgumentList
        return arguments?.parent is KtUserType && arguments.parent?.parent is KtTypeReference
    }

    // --- `when`-entry condition (`else`/`is`/`in`/`!is`/`!in`) ---

    private class WhenCtx(val hasSubject: Boolean)

    /** The [WhenCtx] when [leaf] is at a `when`-entry LABEL position (`when (x) { … █ -> … }`), else null.
     *  Walks up to the first `when` boundary: a [KtWhenEntry] reached from its CONDITION (not its `->`
     *  expression) or the [KtWhenExpression] body directly (a fresh entry being typed) qualifies; a block /
     *  arg-list / class-body in between means we're in an entry's result expression or an inner scope. */
    private fun whenConditionSpot(leaf: KtElement?): WhenCtx? {
        var prev: KtElement? = null
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtWhenEntry -> return if (prev == null || prev !== n.expression) whenCtxOf(n.parent) else null
                // The subject itself (`when (v█)`) is no condition: see [whenSubjectSpot].
                is KtWhenExpression -> return if (prev != null && prev === n.subjectExpression) null else WhenCtx(hasSubject = n.subjectExpression != null)
                is KtBlockExpression, is KtValueArgumentList, is KtClassBody, is KtFile -> return null
            }
            prev = n
            n = n.parent
        }
        return null
    }

    /** The subject of a `when` being written (`when (v█)`): an expression, or a `val` declaring the subject. */
    private fun whenSubjectSpot(leaf: KtElement?): Boolean {
        val ref = leaf?.getParentOfType<KtNameReferenceExpression>(strict = false) ?: return false
        return (ref.parent as? KtWhenExpression)?.subjectExpression === ref
    }

    private fun whenCtxOf(entryParent: KtElement?): WhenCtx =
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
    private fun tryContinuationSpot(leaf: KtElement?): Boolean {
        // The half-typed `catch`/`finally` may attach to the try (a malformed clause) or sit as the next
        // sibling. Cover both: a KtTryExpression ancestor whose try block already closed before the caret, or
        // a preceding sibling that is a KtTryExpression.
        // A `try` that already has its `finally` takes no further clause.
        val caret = leaf?.textRange?.startOffset ?: return false
        leaf.getParentOfType<KtTryExpression>(strict = false)?.let { t ->
            val tryBlockEnd = t.tryBlock.textRange.endOffset
            if (caret > tryBlockEnd) return t.finallyBlock == null || caret < t.finallyBlock!!.textRange.startOffset
        }
        // A `try` that ends an `if` statement before the caret (`if (p) try { } catch (e: E) { }`) goes on too.
        var prev = prevMeaningfulSibling(leaf) ?: prevMeaningfulSiblingUp(leaf)
        while (prev is KtIfExpression) prev = prev.`else` ?: prev.then
        val before = prev as? KtTryExpression ?: return false
        return before.finallyBlock == null
    }

    /** True right after an `if (…) { }` that has no `else` yet — where `else` continues the statement. Either
     *  the leaf is inside the KtIfExpression past its then-branch (a malformed `else` being typed), or the
     *  previous meaningful sibling is an else-less KtIfExpression. */
    private fun elseContinuationSpot(leaf: KtElement?): Boolean {
        ((prevMeaningfulSibling(leaf) ?: prevMeaningfulSiblingUp(leaf)) as? KtIfExpression)?.let {
            if (it.`else` == null) return true
        }
        val caret = leaf?.textRange?.startOffset ?: return false
        leaf.getParentOfType<KtIfExpression>(strict = false)?.let { ife ->
            val thenEnd = ife.then?.textRange?.endOffset ?: return false
            if (ife.`else` == null && caret > thenEnd) return true
        }
        return false
    }

    /** True right after a `do { }` missing its `while` — where `while` continues the loop. */
    private fun doWhileContinuationSpot(leaf: KtElement?): Boolean {
        ((prevMeaningfulSibling(leaf) ?: prevMeaningfulSiblingUp(leaf)) as? KtDoWhileExpression)?.let {
            if (it.condition == null) return true
        }
        val caret = leaf?.textRange?.startOffset ?: return false
        leaf.getParentOfType<KtDoWhileExpression>(strict = false)?.let { d ->
            val bodyEnd = d.body?.textRange?.endOffset ?: return false
            if (d.condition == null && caret > bodyEnd) return true
        }
        return false
    }

    /** The property [leaf] would give an accessor to (right after a `val`/`var` declaration), or null. `get`/
     *  `set` continues such a property; the caller offers only the accessors it still lacks (`set` needs a
     *  `var`). Either the leaf is inside a KtProperty (a malformed accessor being typed) after its name/type,
     *  or the previous declaration sibling is a KtProperty. */
    private fun accessorTarget(leaf: KtElement?): KtProperty? {
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
    private fun prevMeaningfulSibling(leaf: KtElement?): KtElement? {
        // `prevSibling` already skips trivia (the facade's children exclude it), so only the ZERO-WIDTH
        // siblings are left to step over: a file carries a package directive and an import list even when it
        // has neither, and an empty one of those sits between the caret and what precedes it.
        var s = leaf?.prevSibling
        while (s != null && s.textLength == 0) s = s.prevSibling
        return s
    }

    /** The previous meaningful sibling of the nearest ancestor of [leaf] that HAS one — for a caret parsed into
     *  an error/leaf nested below the declaration it follows. */
    private fun prevMeaningfulSiblingUp(leaf: KtElement?): KtElement? {
        var n: KtElement? = leaf
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
    private val TOP_MEMBER = setOf(Place.TOP_LEVEL, Place.MEMBER)
    private val EXPR = setOf(Place.EXPRESSION)

    /**
     * A template: the [places] it fits, the classes that must be on the classpath for it to apply ([requires]),
     * the ones its text uses ([imports], added when the file lacks them), and [build], which may read the code
     * around the caret ([TemplateEnv]) and returns null when it lacks what it needs (`logm` outside a function).
     */
    private class Template(
        val key: String,
        val preview: String,
        val description: String,
        val places: Set<Place>,
        val requires: List<String> = emptyList(),
        val imports: List<String> = requires,
        val build: (TemplateEnv) -> SnippetExpansion?,
    ) {
        constructor(key: String, preview: String, description: String, places: Set<Place>, expansion: SnippetExpansion) :
            this(key, preview, description, places, build = { expansion })
    }

    /** What a template can read from where it expands: the enclosing function and class, and the [place]. */
    private class TemplateEnv(private val leaf: KtElement?, val place: Place) {
        val function: KtNamedFunction? by lazy { leaf?.getParentOfType<KtNamedFunction>(strict = false) }

        /** The class a log tag is named after: the enclosing one (a companion counts as its outer class), else
         *  the file's first, else the file's own name. */
        val className: String? by lazy {
            var c = leaf?.getParentOfType<KtClassOrObject>(strict = false)
            while (c != null && (c.name == null || c.name == "Companion")) c = c.getParentOfType<KtClassOrObject>(strict = true)
            c?.name ?: leaf?.getParentOfType<KtFile>(strict = false)?.let { f ->
                f.declarations.firstNotNullOfOrNull { (it as? KtClassOrObject)?.name } ?: f.name.substringBeforeLast('.')
            }
        }

        /** Whether the enclosing class is an `object` (or companion), where a `const val` is allowed. */
        val inObject: Boolean by lazy { leaf?.getParentOfType<KtClassOrObject>(strict = false) is KtObjectDeclaration }
    }

    private const val LOG = "android.util.Log"
    private const val TOAST = "android.widget.Toast"
    private const val COMPOSABLE = "androidx.compose.runtime.Composable"
    private const val PREVIEW = "androidx.compose.ui.tooling.preview.Preview"

    /** Android's log tags are at most 23 characters, so `logt` cuts a longer class name, as Android Studio does. */
    private const val MAX_TAG = 23

    /** `Log.x(TAG, "function: |")`, as Android Studio's `logd` family writes it. */
    private fun log(level: Char) = Template(
        "log$level", "Log.$level(TAG, \"function: \")", "Log.$level with the enclosing function's name", ST,
        requires = listOf(LOG),
    ) { env ->
        snippet {
            text("Log.$level(TAG, \"")
            env.function?.name?.let { text("$it: ") }
            finalHere()
            text("\")")
        }
    }

    /** A block declaration `keyword Name {` with the caret in its body. */
    private fun block(head: String, name: String) =
        snippet { text("$head "); stop(1, name); text(" {\n    "); finalHere(); text("\n}") }

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
        Template("iter", "for (item in items) { }", "for-each loop", ST,
            snippet { text("for ("); stop(1, "item"); text(" in "); stop(2, "items"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("ifn", "if (x == null) { }", "if null check", ST,
            snippet { text("if ("); stop(1, "x"); text(" == null) {\n    "); finalHere(); text("\n}") }),
        Template("inn", "if (x != null) { }", "if not-null check", ST,
            snippet { text("if ("); stop(1, "x"); text(" != null) {\n    "); finalHere(); text("\n}") }),
        // --- printing ---
        Template("sout", "println()", "Print to standard output", ST,
            snippet { text("println("); finalHere(); text(")") }),
        Template("serr", "System.err.println()", "Print to standard error", ST, requires = listOf("java.lang.System"), imports = emptyList()) {
            snippet { text("System.err.println("); finalHere(); text(")") }
        },
        Template("soutv", "println(\"value = \${value}\")", "Print a value with its name", ST,
            snippet { text("println(\""); stop(1, "value"); text(" = \${"); stop(1, "value"); text("}\")"); finalHere() }),
        // --- declarations ---
        Template("void", "fun name() { }", "function returning nothing", DECL,
            snippet { text("fun "); stop(1, "name"); text("("); stop(2); text(") {\n    "); finalHere(); text("\n}") }),
        Template("exfun", "fun Receiver.name() { }", "extension function", TOP_MEMBER,
            snippet { text("fun "); stop(1, "Receiver"); text("."); stop(2, "name"); text("("); stop(3); text(") {\n    "); finalHere(); text("\n}") }),
        Template("exval", "val Receiver.name: Type get() =", "extension property", TOP_MEMBER,
            snippet { text("val "); stop(1, "Receiver"); text("."); stop(2, "name"); text(": "); stop(3, "Type"); text("\n    get() = "); finalHere() }),
        Template("exvar", "var Receiver.name: Type get() = … set(value) { }", "mutable extension property", TOP_MEMBER,
            snippet {
                text("var "); stop(1, "Receiver"); text("."); stop(2, "name"); text(": "); stop(3, "Type")
                text("\n    get() = "); finalHere(); text("\n    set(value) {\n    }")
            }),
        Template("interface", "interface Name { }", "interface", TOP_MEMBER, block("interface", "Name")),
        Template("singleton", "object Name { }", "singleton object", TOP_MEMBER, block("object", "Name")),
        Template("anonymous", "object : Type { }", "anonymous object", ST_EXPR,
            snippet { text("object : "); stop(1, "Type"); text(" {\n    "); finalHere(); text("\n}") }),
        Template("closure", "{ x -> }", "lambda", EXPR,
            snippet { text("{ "); stop(1, "x"); text(" -> "); finalHere(); text(" }") }),
        // --- Android ---
        Template("logt", "private const val TAG = \"Class\"", "A log tag named after the class", TOP_MEMBER, requires = listOf(LOG), imports = emptyList()) { env ->
            val tag = (env.className ?: "TAG").take(MAX_TAG)
            // `const` needs a top-level or object scope; a plain class gets a regular property.
            val decl = if (env.place == Place.TOP_LEVEL || env.inObject) "private const val" else "private val"
            snippet { text("$decl TAG = \""); stop(1, tag); text("\""); finalHere() }
        },
        log('d'), log('i'), log('w'), log('e'),
        Template("logm", "Log.d(TAG, \"function() called with: a = \$a\")", "Log the function call with its parameters", ST,
            requires = listOf(LOG)) { env ->
            val fn = env.function ?: return@Template null
            val name = fn.name ?: return@Template null
            val params = fn.valueParameters.mapNotNull { it.name }
            val message = if (params.isEmpty()) "$name() called"
            else "$name() called with: " + params.joinToString(", ") { "$it = \$$it" }
            snippet { text("Log.d(TAG, \"$message\")"); finalHere() }
        },
        Template("Toast", "Toast.makeText(this, \"\", Toast.LENGTH_SHORT).show()", "Show a toast", ST, requires = listOf(TOAST)) {
            snippet { text("Toast.makeText("); stop(1, "this"); text(", \""); stop(2); text("\", Toast.LENGTH_SHORT).show()"); finalHere() }
        },
        // --- Compose ---
        Template("comp", "@Composable fun Name() { }", "Composable function", TOP_MEMBER, requires = listOf(COMPOSABLE)) {
            snippet { text("@Composable\nfun "); stop(1, "Name"); text("() {\n    "); finalHere(); text("\n}") }
        },
        Template("prev", "@Preview @Composable fun NamePreview() { }", "Preview Composable function", TOP,
            requires = listOf(COMPOSABLE, PREVIEW)) {
            snippet { text("@Preview\n@Composable\nfun "); stop(1, "Name"); text("Preview() {\n    "); finalHere(); text("\n}") }
        },
    )
}
