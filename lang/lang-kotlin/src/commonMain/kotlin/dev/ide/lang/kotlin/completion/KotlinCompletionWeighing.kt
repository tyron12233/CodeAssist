package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtDeclarationWithBody
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtOperationReferenceExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.expectedLambdaShape
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * Where the keywords of an expression or declaration position rank, the way the IntelliJ Kotlin plugin's
 * `kotlin.kind` weigher places them: after the callables (locals, members, functions), before the
 * classifiers, and `return` / `null` first of all where the code around the caret all but asks for them.
 *
 * The ranking chain is shared with the other backends, so the keywords are placed through the relevance they
 * carry: a non-positive sort priority puts them in the symbols' tier, [KEYWORD_PROXIMITY]
 * between the callables and the classifiers, and the context boost lifts a likely `return` or `null` over
 * everything that does not fit the expected type. Pure PSI checks on the caret's own ancestry: nothing here
 * resolves a name, so a keystroke pays a short parent walk.
 */
internal object KotlinCompletionWeighing {

    /** The keywords' place on [KotlinCompletion]'s proximity scale: after the top-level callables, before the
     *  named arguments and the classifiers. */
    const val KEYWORD_PROXIMITY = 4

    /** A named argument's place on that scale: after the keywords, before the classifiers. */
    const val NAMED_ARGUMENT_PROXIMITY = 5

    /**
     * The keyword items of a bare name position re-ranked as above. [nameRef] is the reference being completed,
     * [expected] the type the context wants there, if known, and [thisType] the type a `this` / `this@label`
     * keyword stands for at the caret.
     */
    fun placeKeywords(
        items: MutableList<CompletionItem>,
        nameRef: KtExpression?,
        expected: KotlinType?,
        /** What an untyped keyword adds to its popularity where a type is expected (`ExpectedTypeWeigher`'s
         *  NON_TYPABLE over NOT_MATCHES), 0 elsewhere. */
        untypedBonus: Int,
        thisType: (String) -> KotlinType?,
    ) {
        val returnLikely = nameRef != null && returnIsLikelyAt(nameRef)
        // `null` where nothing else is known about the slot (an argument of an unresolved call, an operand of a
        // comparison) is what a user is most likely after; with an expected type it is judged by that type, and
        // where `Any?` is wanted it is the one value that fits it best.
        val nullBoost = if (expected == null) nameRef != null && nullIsLikelyAt(nameRef)
        else expected.nullable && expected.qualifiedName == "kotlin.Any"
        for (i in items.indices) {
            val item = items[i]
            if (item.kind != CompletionItemKind.KEYWORD) continue
            val label = item.label
            val isReturn = label == "return" || label.startsWith("return ")
            val isNull = label == "null"
            // The keywords that are values are judged against the expected type like any value: `null` fits a
            // nullable slot and mismatches any other, `true`/`false` a Boolean one, `this` by what it is here.
            val verdict: Boolean? = when {
                expected == null -> null
                item.relevance?.fitsExpectedType == true -> true
                isNull -> expected.nullable
                label == "true" || label == "false" -> expected.qualifiedName == "kotlin.Boolean"
                // `ThisKeywordHandler` judges `this` by the receiver's type, a mismatch included.
                label == "this" || label.startsWith("this@") ->
                    thisType(label)?.let { t -> t.qualifiedName == expected.qualifiedName || expected.isAssignableFrom(t) }
                else -> null
            }
            items[i] = item.copy(
                sortPriority = 0,
                relevance = CompletionRelevance(
                    fitsExpectedType = verdict == true,
                    contextBoost = (isReturn && returnLikely) || (isNull && nullBoost),
                    proximity = (if (verdict == false) MISMATCH_BAND else UNTYPED_BAND) * BAND + KEYWORD_PROXIMITY,
                    popularity = if (verdict == null) untypedBonus else 0,
                ),
            )
        }
    }

    /**
     * Named-argument items (`name =`, `flag = true`) ranked as IntelliJ ranks them: as fitting the expected
     * type whatever it is (a parameter name always fits the slot it names), after the keywords, and first of
     * all where named arguments are what is being written: the list already names one, or spans lines.
     */
    fun placeNamedArguments(items: List<CompletionItem>, preferred: Boolean): List<CompletionItem> = items.map { item ->
        if (item.kind != CompletionItemKind.PARAMETER || !item.label.contains(" =")) item
        else item.copy(
            relevance = CompletionRelevance(
                fitsExpectedType = true,
                contextBoost = preferred,
                proximity = NAMED_ARGUMENT_PROXIMITY,
            ),
        )
    }

    /** How far apart [KotlinCompletion]'s expected-type bands sit on the proximity scale: wider than every kind
     *  proximity, so the band always decides first. */
    const val BAND = 10

    // The proximity bands of [KotlinCompletion]'s expected-type verdicts a keyword can get.
    private const val UNTYPED_BAND = 1
    private const val MISMATCH_BAND = 2

    /**
     * The `when (subject)` whose branch CONDITION the caret is in (`when (e) { <caret> }`, `is A, <caret> ->`),
     * or null: not a `when`, a subjectless one, or the caret in a branch's body.
     */
    fun whenSubjectConditionAt(leaf: KtElement?): KtWhenExpression? {
        var prev: KtElement? = null
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtWhenEntry -> {
                    if (prev != null && prev === n.expression) return null
                    return (n.parent as? KtWhenExpression)?.takeIf { it.subjectExpression != null }
                }
                is KtWhenExpression -> return n.takeIf { it.subjectExpression != null && prev !== it.subjectExpression }
                is KtBlockExpression, is KtValueArgumentList, is KtClassBody, is KtFile -> return null
            }
            prev = n
            n = n.parent
        }
        return null
    }

    /**
     * A `when (subject)` branch condition offered as IntelliJ offers it: each class among the candidates also as
     * an `is X` check (the class name alone is no value of the subject's type; an `object` is, and stays as it
     * is), and an `else -> ` branch while the `when` has none. The `is` checks rank with the values that fit the
     * subject, the `else` right after them, the bare class names below.
     *
     * A condition that fits the subject is named by its class name relative to its package, as IntelliJ's
     * `K2WhenWithSubjectConditionContributor` names a sealed inheritor (`is SEALED.AAAA`, `SEALED.BBBB` for one
     * nested in the sealed class): [relativeName] gives that name, null for a top-level class.
     */
    fun whenConditionItems(
        items: List<CompletionItem>,
        subjectFqn: String?,
        isObject: (KotlinSymbol) -> Boolean,
        relativeName: (KotlinSymbol) -> String? = { null },
        checks: IsChecks = IsChecks.ALL,
        /** The scope a classifier is in by name (`ClassifierWeigher`: an explicit import, the package, a star or a
         *  default import), which orders the checks on a subject of any type before their names do. */
        scopeOf: (KotlinSymbol) -> Int = { 0 },
    ): List<CompletionItem> {
        val out = ArrayList<CompletionItem>(items.size + 8)
        for (item in items) {
            val sym = item.symbol as? KotlinSymbol
            // `K2WhenWithSubjectConditionContributor` checks an enum subject against its entries only and a sealed
            // one against its inheritors only; the other classes stay plain classifiers there.
            val checked = when (checks) {
                IsChecks.ALL -> true
                IsChecks.FITTING -> item.relevance?.fitsExpectedType == true
                IsChecks.NONE -> false
            }
            if (sym == null || (sym.kind != SymbolKind.CLASS && sym.kind != SymbolKind.INTERFACE) || isObject(sym) || !checked) {
                val nested = if (sym != null && item.relevance?.fitsExpectedType == true && sym.kind in CLASS_KINDS) relativeName(sym) else null
                // Written through its outer class, a nested class needs no import of its own.
                out += if (nested == null) item else item.copy(
                    label = nested, insertText = nested, additionalEdits = emptyList(),
                    relevance = item.relevance?.copy(inScope = true),
                )
                continue
            }
            val nested = if (item.relevance?.fitsExpectedType == true) relativeName(sym) else null
            val relevance = (item.relevance ?: CompletionRelevance()).let { if (nested != null) it.copy(inScope = true) else it }
            // Each check right beside its class, so a page cut at the item cap keeps the pairs together.
            out += item.copy(
                label = "is " + (nested ?: item.label),
                insertText = "is " + (nested ?: item.insertText.substringBefore('(').substringBefore('<')),
                additionalEdits = if (nested != null) emptyList() else item.additionalEdits,
                caret = CaretAction.AtEnd,
                // A check on a subtype of the subject fits it; one on an unrelated class, or on the subject's own
                // type (always true), ranks as that class.
                relevance = if (relevance.fitsExpectedType && (sym.type as? KotlinType)?.qualifiedName != subjectFqn) {
                    relevance.copy(proximity = CONDITION_PROXIMITY)
                } else relevance.copy(fitsExpectedType = false),
            )
            out += item.copy(relevance = relevance.copy(fitsExpectedType = false, proximity = relevance.proximity % BAND + UNTYPED_BAND * BAND))
        }
        // The conditions that fit (the `is` checks on subtypes, the objects) in one alphabetical run, as IntelliJ
        // lists them, ahead of the rest: the engine keeps this order among equally ranked items.
        val (fitting, rest) = out.partition { it.relevance?.let { r -> r.fitsExpectedType && r.proximity == CONDITION_PROXIMITY } == true }
        val sorted = if (checks != IsChecks.ALL) fitting.sortedBy { (it.symbol as? KotlinSymbol)?.name ?: it.label }
        else fitting.sortedWith(compareBy({ (it.symbol as? KotlinSymbol)?.let(scopeOf) ?: 0 }, { (it.symbol as? KotlinSymbol)?.name ?: it.label }))
        return sorted.map { it.copy(sortPriority = 0) } + rest
    }

    /** Which classes [whenConditionItems] offers as `is X` checks: every one (a subject of any other type), the
     *  ones that fit (a sealed subject's inheritors), or none (an enum subject, checked against its entries). */
    enum class IsChecks { ALL, FITTING, NONE }

    /**
     * The order of a `when (subject)` branch condition, IntelliJ's `CompletionContributorGroupWeigher` there: what
     * the `when` contributor offers (the `is X` checks, the enum entries, the sealed objects, `null`, `else`) and
     * the keywords come first, the classifiers next, every other name last; within each group the expected-type
     * verdict decides (`MATCHES_PREFERRED` for an enum entry, then `MATCHES`, then the untyped, then the
     * mismatches), then the kind (`KindWeigher`: an enum entry, a keyword, the rest).
     *
     * The group outranks the expected type there, while the engine compares the expected type first, so both ride
     * the popularity key (the expected-type flag is cleared for all of them) and the kind the proximity of the
     * `when` group. [isObject] tells a sealed `object` (offered by the `when` contributor itself) from a class.
     */
    fun rankWhenConditionGroups(items: List<CompletionItem>, isObject: (KotlinSymbol) -> Boolean, subjectName: String?): List<CompletionItem> =
        items.map { item ->
            val r = item.relevance ?: CompletionRelevance.NONE
            val sym = item.symbol as? KotlinSymbol
            val label = item.label
            val fits = r.fitsExpectedType
            val keyword = item.kind == CompletionItemKind.KEYWORD
            val qualifiedEntry = item.kind == CompletionItemKind.ENUM_CONSTANT && '.' in label
            val classifier = item.kind in CLASSIFIER_ITEM_KINDS
            val group = when {
                keyword || label.startsWith("is ") || qualifiedEntry -> 0
                classifier -> if (fits && sym != null && isObject(sym)) 0 else 1
                else -> 2
            }
            val tier = when {
                keyword -> if (fits && label in TYPED_KEYWORDS) 1 else 2
                fits -> if (item.kind == CompletionItemKind.ENUM_CONSTANT) 0 else 1
                r.proximity / BAND >= MISMATCH_BAND -> 3
                else -> 2
            }
            val proximity = if (group != 0) {
                // The subject itself is `KindWeigher`'s SYMBOL_TO_SKIP: comparing it with itself is no condition.
                if (group == 2 && subjectName != null && sym?.name == subjectName && sym.kind != SymbolKind.METHOD) SUBJECT_PROXIMITY
                else r.proximity
            } else when {
                item.kind == CompletionItemKind.ENUM_CONSTANT -> 0
                keyword -> 2
                else -> 4
            }
            item.copy(
                relevance = r.copy(
                    fitsExpectedType = false,
                    contextBoost = false,
                    popularity = (2 - group) * WHEN_GROUP_SCALE + (3 - tier) * WHEN_TIER_SCALE + r.popularity,
                    proximity = proximity,
                ),
            )
        }

    /** The scales [rankWhenConditionGroups] puts the group and the verdict on, above any other popularity (the
     *  deprecation penalty is a million). */
    private const val WHEN_GROUP_SCALE = 100_000_000
    private const val WHEN_TIER_SCALE = 10_000_000

    /** Where the `when` subject ranks among its own conditions: after every other kind. */
    private const val SUBJECT_PROXIMITY = 1_000

    /** The keywords that have a type (`KotlinExpectedTypeWeigher.keywordType`); any other is untyped. */
    private val TYPED_KEYWORDS = setOf("null", "true", "false")

    private val CLASSIFIER_ITEM_KINDS = setOf(
        CompletionItemKind.CLASS, CompletionItemKind.INTERFACE, CompletionItemKind.ENUM,
        CompletionItemKind.ANNOTATION_TYPE, CompletionItemKind.RECORD, CompletionItemKind.TYPE_PARAMETER,
    )

    /** The `else -> ` branch for [whenExpr] while it has none, ranked right after the conditions that fit. */
    fun elseBranchItem(whenExpr: KtWhenExpression, prefix: String): CompletionItem? {
        if (whenExpr.entries.any { it.isElse } || !"else".startsWith(prefix)) return null
        return CompletionItem(
            label = "else -> ",
            insertText = "else -> ",
            kind = CompletionItemKind.KEYWORD,
            sortPriority = 0,
            relevance = CompletionRelevance(fitsExpectedType = true, proximity = CONDITION_PROXIMITY + 1),
        )
    }

    /** The classifier kinds a `when` condition names (an `object` is a CLASS). */
    private val CLASS_KINDS = setOf(SymbolKind.CLASS, SymbolKind.INTERFACE)

    /** Where an `is X` branch condition ranks: with the values that fit (an `object` of the subject's type). */
    private const val CONDITION_PROXIMITY = 2

    /**
     * The names a candidate is compared against by IntelliJ's `kotlin.preferMatchingArgumentName`: at a call
     * argument, the name of the parameter it fills (in any overload); at the type of a parameter or property
     * being declared (`apple: <caret>`), that declaration's name. Empty elsewhere. [leaf] is the caret's leaf.
     */
    fun referenceNames(leaf: KtElement?, resolver: KotlinResolver): List<String> {
        var n: KtElement? = leaf?.parent
        while (n is KtNameReferenceExpression || n is KtUserType || n is KtTypeReference) {
            if (n is KtTypeReference) {
                val owner = n.parent
                return if (owner is KtParameter || owner is KtProperty) listOfNotNull((owner as KtNamedDeclaration).name) else emptyList()
            }
            n = n.parent
        }
        val ref = leaf?.parent as? KtNameReferenceExpression ?: return emptyList()
        val arg = ref.parent as? KtValueArgument ?: return emptyList()
        // The value of a named argument (`User(name = <caret>)`) is compared with the name it is given.
        arg.getArgumentName()?.asName?.identifier?.let { named ->
            return if (arg.getArgumentExpression() === ref) listOf(named) else emptyList()
        }
        val list = arg.parent as? KtValueArgumentList ?: return emptyList()
        val call = list.parent as? KtCallExpression ?: return emptyList()
        val index = list.arguments.indexOf(arg)
        val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        return targets.mapNotNull { s ->
            val i = if (s.varargParamIndex in 0..<index) s.varargParamIndex else index
            s.paramNames.getOrNull(i)
        }.distinct()
    }

    /**
     * How closely [name] matches the best of [references], IntelliJ's argument-name similarity as a score
     * (higher is closer, 0 unrelated): 100 the same name, 99 every word of the reference in another order or
     * among more (`someName` for `name`), else the share of the reference's words it has.
     */
    fun nameSimilarity(name: String, references: List<String>): Int {
        var best = 0
        for (ref in references) {
            val score = if (ref.equals(name, ignoreCase = true)) 100 else {
                val refWords = words(ref)
                val matched = refWords.intersect(words(name).toSet()).count { w -> !w.all { it.isDigit() } }
                when {
                    matched == 0 || refWords.isEmpty() -> 0
                    matched >= refWords.size -> 99
                    else -> (matched * 98) / refWords.size
                }
            }
            if (score > best) best = score
        }
        return best
    }

    /** [name] split into its lowercase words: at camel humps, underscores, spaces and digits. */
    private fun words(name: String): List<String> {
        val out = ArrayList<String>(4)
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString().lowercase(); cur.setLength(0) } }
        for (i in name.indices) {
            val c = name[i]
            if (!c.isLetterOrDigit()) { flush(); continue }
            val prev = name.getOrNull(i - 1)
            val next = name.getOrNull(i + 1)
            val boundary = prev != null && prev.isLetterOrDigit() && (
                (c.isUpperCase() && (prev.isLowerCase() || prev.isDigit())) ||
                    (c.isUpperCase() && prev.isUpperCase() && next != null && next.isLowerCase()) ||
                    (c.isDigit() != prev.isDigit())
                )
            if (boundary) flush()
            cur.append(c)
        }
        flush()
        return out
    }

    /**
     * The type an operand position expects where the resolver's own expected type has none, for ranking only:
     * an operand of `==`/`!=` (`===`/`!==`) the other operand's type, so `f == Candi` offers the values `f`
     * can equal first, and the right side of an elvis its left side's type without the `?` (`a ?: ` wants what
     * `a` would have been). [ref] is the name being completed; a `receiver.name` selector and parentheses
     * around it are looked through.
     */
    fun operandExpectedType(ref: KtExpression?, resolver: KotlinResolver): KotlinType? {
        var e: KtElement = ref ?: return null
        (e.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === e }?.let { e = it }
        while (true) e = (e.parent as? KtParenthesizedExpression) ?: break
        val bin = e.parent as? KtBinaryExpression ?: return null
        val other = when {
            bin.left === e -> bin.right
            bin.right === e -> bin.left
            else -> null
        } ?: return null
        val equality = when (bin.operationToken) {
            KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> true
            KtTokens.ELVIS -> if (bin.right === e) false else return null
            else -> return null
        }
        val type = runCatching { resolver.inferType(other) }.getOrNull() ?: return null
        if (type.isTypeParameter || type.qualifiedName == "kotlin.Nothing") return null
        // An equality may compare with `null` whatever the other side is (`getEqualityExpectedType` makes it
        // nullable); an elvis wants what its left side would have been.
        return type.withNullable(equality)
    }

    /**
     * The type two value slots expect that the resolver's own expected type leaves open, for ranking only: the
     * expression body of a getter (`val x: Foo get() = <caret>`) the property's declared type, and the value of a
     * labeled return (`return@forEach <caret>`) the return type of the lambda that label names. [ref] is the name
     * being completed; [labelOf] gives a lambda's label (its own or its call's name).
     */
    fun accessorOrLabeledReturnExpectedType(
        ref: KtExpression?, resolver: KotlinResolver, labelOf: (KtLambdaExpression) -> String?,
    ): KotlinType? {
        val e = ref ?: return null
        when (val p = e.parent) {
            is KtPropertyAccessor -> {
                if (!p.isGetter || p.bodyExpression !== e) return null
                val property = p.parent as? KtProperty ?: return null
                val text = property.typeReference?.text ?: return null
                return runCatching { resolver.service.typeFromText(text, resolver.fileContext) }.getOrNull()
            }
            is KtReturnExpression -> {
                if (p.returnedExpression !== e) return null
                val label = p.getLabelName() ?: return null
                var n: KtElement? = p.parent
                // Out through the lambdas (a function literal is a declaration with a body too) to the function.
                while (n != null && n !is KtNamedFunction && n !is KtPropertyAccessor && n !is KtClassOrObject) {
                    if (n is KtLambdaExpression && labelOf(n) == label) {
                        val shape = runCatching { resolver.expectedLambdaShape(n) }.getOrNull() ?: return null
                        return (shape.returnType as? KotlinType)?.takeIf { !it.isTypeParameter && it.qualifiedName != "kotlin.Unit" }
                    }
                    n = n.parent
                }
                return null
            }
            else -> return null
        }
    }

    /**
     * Whether a lambda's body lies between [ref] and anything that states a type for it (an argument, an
     * initializer, a `return`, a condition, an operator): the resolver's expected type walks on through the
     * lambda to whatever wants the lambda's CALL, which says nothing about a statement inside it
     * (`Counter().also { it.<caret> }` in a function returning `Counter`). Member ranking leaves it out then.
     */
    fun insideLambdaBeforeExpectation(ref: KtElement?): Boolean {
        var n: KtElement? = ref?.parent
        while (n != null) {
            when (n) {
                is KtLambdaExpression -> return true
                is KtValueArgument, is KtProperty, is KtReturnExpression, is KtBinaryExpression, is KtIfExpression,
                is KtWhenEntry, is KtDeclarationWithBody, is KtClassOrObject, is KtFile -> return false
            }
            n = n.parent
        }
        return false
    }

    /**
     * IntelliJ's `isLikelyInPositionForReturn`: [expr] is the last statement of a block (or the single body of
     * an `if`/`when` branch), or the right side of an elvis, in a function body, outside any lambda and not
     * already part of what a `return` returns. The last statement of a Unit function's own body is excluded
     * (falling off its end returns already), and so is a loop's body.
     */
    fun returnIsLikelyAt(expr: KtExpression): Boolean {
        var n: KtElement? = expr.parent
        var decl: KtDeclarationWithBody? = null
        while (n != null) {
            when (n) {
                is KtFunctionLiteral -> return false
                is KtDeclarationWithBody -> { decl = n; break }
                is KtClassOrObject, is KtFile -> return false
            }
            n = n.parent
        }
        if (decl == null || !decl.hasBlockBody()) return false
        val returnsUnit = when (decl) {
            is KtNamedFunction -> decl.typeReference?.text?.trim().let { it == null || it == "Unit" || it == "kotlin.Unit" }
            is KtPropertyAccessor -> !decl.isGetter
            else -> true
        }
        val inUnitBodyItself = returnsUnit && expr.parent?.parent === decl
        return when {
            returnedBy(expr) -> false
            isElvisRight(expr) -> true
            else -> isLastOrSingleStatement(expr) && !isDirectlyInLoopBody(expr) && !inUnitBodyItself
        }
    }

    /**
     * IntelliJ's `isPositionSuitableForNull`: the reference is a call's argument or an operand of a binary
     * operator, the slots whose expected type is most often unknown while the code is half written.
     */
    fun nullIsLikelyAt(expr: KtExpression): Boolean {
        val parent = expr.parent
        if (parent is KtValueArgument) return true
        if (parent is KtBinaryExpression && parent.operationReference !== expr) return true
        return siblingOperation(expr, -1) || siblingOperation(expr, 1)
    }

    private fun siblingOperation(e: KtElement, step: Int): Boolean {
        var s = if (step < 0) e.prevSibling else e.nextSibling
        while (s != null && (s.text.isBlank() || s.text.startsWith("//") || s.text.startsWith("/*"))) {
            s = if (step < 0) s.prevSibling else s.nextSibling
        }
        return s is KtOperationReferenceExpression
    }

    private fun isElvisRight(e: KtElement): Boolean {
        val bin = e.parent as? KtBinaryExpression ?: return false
        return bin.operationToken == KtTokens.ELVIS && bin.right === e
    }

    /** The last statement of a block, or the single expression of a `when` branch or a control structure's body. */
    private fun isLastOrSingleStatement(e: KtElement): Boolean = when (val p = e.parent) {
        is KtBlockExpression -> p.statements.lastOrNull() === e
        is KtWhenEntry, is KtContainerNodeForControlStructureBody -> true
        else -> false
    }

    private fun isDirectlyInLoopBody(e: KtElement): Boolean {
        val container = when (val p = e.parent) {
            is KtBlockExpression -> p.parent as? KtContainerNodeForControlStructureBody
            is KtContainerNodeForControlStructureBody -> p
            else -> null
        }
        return container?.parent is KtLoopExpression
    }

    /** Whether [e] is already (part of) the value a `return` returns: `return x`, `return if (c) x else y`,
     *  `return a ?: x`, the last statement of a returned `when` branch's block. */
    private fun returnedBy(e: KtElement): Boolean {
        var cur: KtElement = e
        while (true) {
            val p = cur.parent ?: return false
            when (p) {
                is KtReturnExpression -> return true
                is KtBinaryExpression -> if (p.operationToken != KtTokens.ELVIS) return false
                is KtContainerNodeForControlStructureBody, is KtIfExpression -> {}
                is KtBlockExpression -> if (!isLastOrSingleStatement(cur)) return false
                is KtWhenEntry -> { cur = p.parent ?: return false; continue }
                else -> return false
            }
            cur = p
        }
    }
}
