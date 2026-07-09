package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinPerf
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.parse.climbTo
import dev.ide.lang.kotlin.parse.identifierPrefixBefore
import dev.ide.lang.kotlin.interp.PreviewConstants
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtValueArgumentName

/**
 * Kotlin code completion, using the completion-token technique: splice a dummy identifier at the caret on a
 * copy of the buffer, parse it, find the marker's element, and classify the position ([CompletionPosition]):
 *   • selector of a (safe-)qualified expression -> MemberAccess  -> receiver members ∪ extensions (or a package's members)
 *   • inside a type reference                    -> TypeReference -> visible classifiers
 *   • the operation reference of a binary expr   -> InfixName     -> infix functions applicable to the left operand
 *   • a bare name in expression position         -> NameReference -> scope symbols + visible types
 * Candidates are prefix-filtered, ranked (prefix/proximity), and mapped to neutral [CompletionItem]s by
 * [KotlinCompletionItems]; auto-import edits come from [KotlinAutoImport].
 *
 * In expression position three extra contributions are merged ahead of the plain candidates:
 *   • named arguments  — inside a call's argument list, the callee's not-yet-supplied parameter names (`name = `)
 *   • override stubs   — at a member-declaration spot in a class body, overridable inherited members
 *                        (`override fun foo(): T { TODO(...) }`)
 *   • expected type    — `true`/`false` where a Boolean is wanted and `Enum.CONSTANT` at an enum slot, and the
 *                        ranking floats candidates whose type is assignable to the expected type first.
 */
class KotlinCompletion(
    private val service: KotlinSymbolService,
    /** Run before each completion — the host wires this to sync the symbol model to the live editor buffers,
     *  so a declaration just typed in another open file completes here (cross-file freshness). */
    private val onBeforeComplete: () -> Unit = {},
) : CompletionContributor {

    override val id = "kotlin.completion"

    // Single-slot completion working tree. Completion fires on every keystroke against a marker-spliced copy
    // of the buffer; keeping the last spliced PSI lets us reparse only the changed span (the typed char + the
    // moved marker) instead of re-parsing the whole file each keystroke (the per-keystroke parse cost on a
    // large Compose file). Kept SEPARATE from the analyzer's lastByFile tree because this one carries the
    // marker. Bounded to one file (the focused one); a different path full-parses and replaces it. Touched
    // only on the single serialized engine worker (completion lane), so no synchronization beyond the parse
    // lock that KotlinParserHost.tryReparse already takes.
    private var splicedPath: String? = null
    private var splicedTree: KtFile? = null

    /** Parse [spliced] (the marker-spliced buffer for [path]) by incrementally reparsing the prior spliced tree
     *  when it's the same file, else a full parse; updates the single-slot cache. */
    private fun parseSpliced(name: String, path: String, spliced: String): KtFile {
        val prev = if (splicedPath == path) splicedTree else null
        val kt = prev?.let { KotlinParserHost.tryReparse(it, spliced) } ?: KotlinParserHost.parse(
            name,
            spliced
        )
        splicedPath = path
        splicedTree = kt
        return kt
    }

    override suspend fun fillCompletionVariants(
        params: CompletionParams,
        result: CompletionResultSet
    ) {
        val res = KotlinPerf.trace("kt.complete") { completeInner(params.document, params.offset) }
        result.addAllElements(res.items)
        result.setReplacementRange(res.replacementRange)
        if (res.isIncomplete) result.markIncomplete()
    }

    private fun completeInner(
        document: dev.ide.lang.incremental.DocumentSnapshot,
        requestedOffset: Int
    ): CompletionResult {
        KotlinPerf.span("onBefore") { onBeforeComplete() }
        val original = document.text.toString()
        val offset = requestedOffset.coerceIn(0, original.length)
        val prefix = identifierPrefixBefore(original, offset)
        // The graded matcher every candidate gate below shares: exact/prefix/camel-hump/substring, so
        // `mDL` reaches `myDynamicList` through the same filter chain a plain prefix takes.
        val matcher = PrefixMatcher(prefix)
        val replaceRange = TextRange(offset - prefix.length, offset)
        // The first non-whitespace char after the identifier token the editor will replace (it extends the
        // replacement forward over the rest of the word) — lets an item avoid inserting a second `(`/`{` when
        // the call/lambda syntax is already there (`foo|(x)` must not become `foo()(x)`).
        val followingChar = run {
            var i = offset
            while (i < original.length && (original[i].isLetterOrDigit() || original[i] == '_')) i++
            while (i < original.length && original[i].isWhitespace()) i++
            original.getOrNull(i)
        }

        // Splice the marker right at the caret so the parser yields a real reference node even after `.`.
        val spliced = original.substring(0, offset) + MARKER + original.substring(offset)
        val kt = KotlinPerf.span("parse") {
            parseSpliced(
                document.file.name,
                document.file.path,
                spliced
            )
        }
        val parsed = KotlinParsedFile(kt, document.file, document.version)
        // Same-file freshness: a class/member declared in THIS buffer (`with(LocalClass()) { … }`) resolves from
        // the live PSI. Keyed by the marker-free text hash so it shares the focal entry analyze/highlight set
        // (a no-op when already synced); the marker sits at the caret, leaving referenced declarations intact.
        runCatching {
            service.syncFocal(document.file.path, original.hashCode()) {
                dev.ide.lang.kotlin.symbols.SourceIndexBuilder.extractFrom(
                    kt,
                    parsed,
                    document.file.path
                )
            }
        }
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        if (insideStringLiteral(markerLeaf)) {
            return CompletionResult(
                emptyList(),
                isIncomplete = false,
                replacementRange = replaceRange
            )
        }
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf)
        // A callable REFERENCE — `::foo`, `receiver::foo`, `this::foo` — completes the name after `::`. A function
        // there must insert its BARE name (`::foo`), never a call (`::foo()`) or a trailing lambda: a `::` reference
        // is a function value, not an invocation. Covers the unbound `::name` (a NameReference position) and the
        // `receiver::name` member reference alike.
        val callableRef =
            nameRef?.let { (it.parent as? KtCallableReferenceExpression)?.callableReference === it } == true
        // A type/callable completed by simple name needs an `import` unless already visible; the auto-import
        // context answers that and in-scope candidates rank first, so accepting an unimported symbol imports it.
        val autoImport = KotlinAutoImport(kt, resolver.fileContext)

        val pos = KotlinPerf.span("candidates") {
            when (val where = classifyPosition(markerLeaf, nameRef)) {
                is CompletionPosition.MemberAccess -> memberAccessCandidates(
                    where.receiver,
                    offset,
                    resolver,
                    matcher,
                    where.callableRef
                )
                // A type slot also carries a few keyword positions (a `where` clause after a generic
                // signature); KotlinKeywords returns nothing for a plain type reference, so this is safe.
                CompletionPosition.TypeReference -> PositionResult(
                    service.typeNamesByPrefix(prefix),
                    keywordContext = true
                )

                is CompletionPosition.InfixName -> infixCandidates(
                    where.left,
                    offset,
                    resolver,
                    matcher
                )

                CompletionPosition.NameReference -> nameReferenceCandidates(
                    markerLeaf,
                    offset,
                    matcher,
                    resolver,
                    autoImport
                )
            }
        }
        val raw = pos.raw

        // Inside a @Composable context (a `setContent`/`Column` content lambda, a @Composable function body),
        // float @Composable callables to the top — Android Studio's Compose weigher. NOT a filter: non-composable
        // code (`remember`, `println`, locals, control flow) stays available, just ranked below.
        val composableContext = KotlinPerf.span("composeCtx") {
            resolver.composableContextAt(offset) == ComposableContext.COMPOSABLE
        }

        val candidates = KotlinPerf.span("rank") {
            raw.asSequence()
                .filter { it.name != "_" && matcher.matches(it.name) && MARKER !in it.name }
                .distinctBy { it.name + "#" + it.kind + "#" + (it.signature ?: "") }
                .map { s ->
                    Candidate(
                        symbol = s,
                        importEdit = if (pos.packageCompletion) emptyList() else importEditFor(
                            s,
                            autoImport
                        ),
                        fitsExpected = pos.expected != null && matchesExpected(s, pos.expected),
                        grade = matcher.grade(s.name)?.ordinal ?: PrefixMatcher.Grade.entries.size,
                        boosted = composableContext && s.isComposable,
                        group = memberGroup(s),
                    )
                }
                .sortedWith(rank())
                .take(MAX_ITEMS)
                .toList()
        }

        val symbolItems = candidates.map {
            KotlinCompletionItems.toItem(
                it.symbol,
                it.importEdit,
                followingChar,
                it.relevance(),
                infix = pos.infixInsert,
                callableRef = callableRef
            )
        }

        // Keyword / live-template contributions, appended after the symbol candidates so a real symbol always
        // sorts first within a match tier; the editor's match-tier re-sort still floats an exact keyword/template
        // match (`if`, `for`) to the top once the user types it whole.
        val tail = ArrayList<CompletionItem>()
        if (pos.keywordContext) {
            tail += KotlinKeywords.itemsFor(
                leaf = markerLeaf,
                prefix = prefix,
                precedingText = original,
                tokenStart = offset - prefix.length,
                inFunction = KotlinKeywords.isInFunction(markerLeaf),
                inLoop = KotlinKeywords.isInLoop(markerLeaf),
            )
        }

        // Reserve room so the (small) keyword/template/postfix tail is never starved by a large symbol set —
        // otherwise an empty-prefix popup (hundreds of in-scope symbols) would truncate the keywords away.
        val keep = (MAX_ITEMS - tail.size).coerceAtLeast(0)
        val items = ((pos.extra.distinctBy { it.kind to it.label } + symbolItems).take(keep) + tail)
            .distinctBy { it.kind to it.label }
            .take(MAX_ITEMS)
        return CompletionResult(
            items = items,
            isIncomplete = raw.size > MAX_ITEMS,
            replacementRange = replaceRange
        )
    }

    // --- position classification + per-position candidate sourcing ---

    /** Where the caret sits, deciding which candidate set applies (see [KotlinCompletion]'s class doc). */
    private sealed interface CompletionPosition {
        /** `receiver.<caret>` — the selector of a qualified expression, or `receiver::<caret>` (a callable
         *  reference; [callableRef]), where instance members are valid even on a type receiver (unbound refs). */
        class MemberAccess(val receiver: KtExpression, val callableRef: Boolean = false) :
            CompletionPosition

        /** Inside a type reference — only classifiers belong. */
        object TypeReference : CompletionPosition

        /** The operation-reference slot of a binary expression (`a foo█`, `0 downTo█`) — only infix functions
         *  applicable to [left] belong (plus the `as`/`in`/`is` keyword operators from KotlinKeywords). */
        class InfixName(val left: KtExpression) : CompletionPosition

        /** A bare name in expression/statement position. */
        object NameReference : CompletionPosition
    }

    /** The raw candidates for a position plus the side outputs the assembly stage needs (extra non-symbol
     *  items, an expected type to rank by, and the package-completion / keyword-context flags). */
    private class PositionResult(
        val raw: List<KotlinSymbol>,
        val extra: List<CompletionItem> = emptyList(),
        val expected: KotlinType? = null,
        val packageCompletion: Boolean = false,
        val keywordContext: Boolean = false,
        /** The candidates are infix functions at an operator slot → insert the `a foo b` form (`foo `), not
         *  the call form `foo()`. */
        val infixInsert: Boolean = false,
    )

    private fun classifyPosition(
        markerLeaf: PsiElement?,
        nameRef: KtNameReferenceExpression?
    ): CompletionPosition {
        // A callable reference `Receiver::name` (`String::length`, `foo::bar`) — the name after `::` completes
        // from the receiver's members, exactly like a `.` access (bound/unbound is the same candidate set).
        val callableRefReceiver =
            nameRef?.let { (it.parent as? KtCallableReferenceExpression)?.takeIf { p -> p.callableReference === it }?.receiverExpression }
        return when {
            callableRefReceiver != null -> CompletionPosition.MemberAccess(
                callableRefReceiver,
                callableRef = true
            )

            nameRef != null && isSelectorOfQualified(nameRef) ->
                CompletionPosition.MemberAccess((nameRef.parent as KtQualifiedExpression).receiverExpression)

            inTypePosition(markerLeaf) -> CompletionPosition.TypeReference
            else -> infixOperatorLeft(markerLeaf)?.let { CompletionPosition.InfixName(it) }
                ?: CompletionPosition.NameReference
        }
    }

    /** When [leaf] is the operation-reference slot of a binary expression (`a foo█`, `0 downTo█`), the left
     *  operand whose type the infix function must resolve against; null otherwise (a right operand `a + b█` is
     *  a plain name reference, not the operation reference). */
    private fun infixOperatorLeft(leaf: PsiElement?): KtExpression? {
        val ref = climbTo<KtOperationReferenceExpression>(leaf) ?: return null
        val bin = ref.parent as? KtBinaryExpression ?: return null
        return if (bin.operationReference === ref) bin.left else null
    }

    /** `receiver.<caret>` — instance members + extensions, type-receiver statics + companion members, or, when
     *  the receiver is a pure package/FQN path, that package's sub-packages + types (inserted fully-qualified). */
    private fun memberAccessCandidates(
        receiver: KtExpression,
        offset: Int,
        resolver: KotlinResolver,
        matcher: PrefixMatcher,
        callableRef: Boolean = false
    ): PositionResult {
        val prefix = matcher.prefix
        // A bare type-parameter receiver (`t.` where `t: T`, `<T : Bound>`) completes against the parameter's
        // upper bound; a normal type is unchanged, an unbounded parameter drops to the package/empty path.
        val recvType = KotlinPerf.span("infer") {
            resolver.inferType(receiver)?.let { resolver.receiverForMembers(it, receiver.textRange.startOffset) }
        }
        if (recvType != null) {
            // Instance receiver (`listOf("").`) → instance members + extensions; type receiver (`Int.`) →
            // companion ("static") members + nested. Built-ins provide the real Kotlin members + companion (from
            // .kotlin_builtins), so `List.` is naturally empty and `Int.` shows MAX_VALUE. Constructors are never
            // reached via `.`. Prefix-aware: with large classpaths (Compose) a receiver's member+extension set is
            // huge, so push the typed prefix into the symbol service rather than enumerating then filtering.
            // A callable reference (`String::length`) admits BOTH instance (unbound) and static members on a type
            // receiver, so the static-only visibility filter is skipped there.
            val typeReceiver = resolver.isTypeReceiver(receiver)
            val members = KotlinPerf.span("members") {
                service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
            }.filter { callableRef || memberVisibleOn(it, typeReceiver) }
            // A bare `Type.` where the type has a companion object resolves to the companion instance, so the
            // companion's own members (Compose's `Color.Black`/`White`) and the extensions applicable to it
            // (`Modifier.Companion : Modifier` → `Modifier.padding`/`background`) are in scope too — instance-filtered.
            val raw = if (typeReceiver) {
                // An enum's CONSTANTS are reached statically (`Test.A`) but aren't instance members, so
                // `membersForCompletion` (which surfaces `values()`/`valueOf()`/`entries`) never lists them.
                val enumConstants = service.enumConstantsOf(recvType.qualifiedName)
                    .filter { matcher.matches(it.name) }
                members + enumConstants + service.companionMembersFor(
                    recvType.qualifiedName,
                    prefix
                )
                    .filter { memberVisibleOn(it, typeReceiver = false) }
            } else {
                // Member-extensions in scope on an instance receiver (`map.printMap()` where `printMap` is a
                // `Map<…>` extension declared in the enclosing class, `Modifier.weight` inside a `Row { }`).
                members + resolver.scopeMemberExtensions(offset, recvType, prefix)
            }
            return PositionResult(raw)
        }
        // Receiver is a package/FQN prefix (`java.util.`, `android.`) — complete its sub-packages + the types in
        // it (inserted fully-qualified, so it needs no auto-import).
        val pkg = packagePathOf(receiver)
        return if (pkg != null) PositionResult(
            service.packageMembers(pkg, prefix),
            packageCompletion = true
        )
        else PositionResult(emptyList())
    }

    /** `left infixName█` — the infix functions applicable to [left]'s type: its own members plus the extensions
     *  on it (`0 downTo`, `range step`, a user `infix fun`), each filtered to `infix`. The `as`/`in`/`is`
     *  keyword operators that also fit the slot come from KotlinKeywords (keywordContext), so a null-typed or
     *  unresolved left operand still offers those. Scope symbols are NOT offered — an operation reference only
     *  accepts an infix function name, never an arbitrary local. */
    private fun infixCandidates(
        left: KtExpression,
        offset: Int,
        resolver: KotlinResolver,
        matcher: PrefixMatcher
    ): PositionResult {
        val prefix = matcher.prefix
        val recvType = KotlinPerf.span("infer") {
            resolver.inferType(left)?.let { resolver.receiverForMembers(it, left.textRange.startOffset) }
        } ?: return PositionResult(emptyList(), keywordContext = true)
        val members = KotlinPerf.span("members") {
            service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
        }
        val scopeExt = resolver.scopeMemberExtensions(offset, recvType, prefix)
        val raw = (members + scopeExt).filter { it.kind == SymbolKind.METHOD && it.isInfix }
        return PositionResult(raw, keywordContext = true, infixInsert = true)
    }

    /** A bare name in expression/statement position: scope symbols + visible types, plus the override-stub,
     *  named-argument, and expected-type extras, and the keyword/live-template context. */
    private fun nameReferenceCandidates(
        markerLeaf: PsiElement?,
        offset: Int,
        matcher: PrefixMatcher,
        resolver: KotlinResolver,
        autoImport: KotlinAutoImport,
    ): PositionResult {
        val prefix = matcher.prefix
        val extra = ArrayList<CompletionItem>()
        // Member-declaration position in a class body → offer overridable inherited members as stubs.
        if (isOverridePosition(markerLeaf)) {
            resolver.overridableMembersAt(offset)
                .filter { matcher.matches(it.name) }
                .forEach { extra += KotlinCompletionItems.overrideItem(it) }
        }
        // Primary-constructor parameter position → offer the inherited open PROPERTIES as `override val name: T`
        // stubs (a ctor parameter can override a supertype property, never a function). When `override` is
        // already typed on the parameter the stub omits it (the keyword token is likewise suppressed).
        if (isPrimaryCtorParamPosition(markerLeaf)) {
            val overrideTyped =
                climbTo<KtParameter>(markerLeaf)?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true
            resolver.overridableMembersAt(offset)
                .filter { it.kind == SymbolKind.FIELD && matcher.matches(it.name) }
                .forEach { extra += KotlinCompletionItems.ctorOverrideParam(it, overrideTyped) }
        }
        // Inside a call's argument list → offer the callee's not-yet-supplied parameter names. When the caret is
        // on the NAME of an argument that is ALREADY named (`foo(contai|nerColor = x)`), the ` = ` is already
        // present, so insert the bare name (the editor replaces the whole name token) — no duplicated `=`. The
        // arg being edited carries the marker, so its (now-garbled) name never matches a real parameter and so
        // doesn't suppress its own re-completion.
        namedArgCallOf(markerLeaf)?.let { call ->
            val editingName = climbTo<KtValueArgumentName>(markerLeaf) != null
            val supplied = suppliedArgNames(call)
            resolver.callParameters(call)
                .filter { it.name !in supplied && matcher.matches(it.name) }
                .forEach { extra += KotlinCompletionItems.namedArgItem(it, bareName = editingName) }
        }
        // Inside an annotation's argument list → its parameter names (ranked first), plus the enum-ish constants
        // for @Preview's uiMode/device which aren't recoverable from the parameter type alone.
        extra += annotationArgExtras(markerLeaf, matcher, resolver)
        // The marker IS the NAME being given to a declaration (`val foo`, `fun bar`, a parameter) — an
        // identifier the user is inventing, so scope symbols / type names / auto-imports don't belong here.
        // (Any override / ctor-property / named-argument stubs above still apply: they're the *right* offers at
        // a member/ctor-param name spot.) A value/function/class/typealias name admits no keywords either (the
        // declaring keyword is already typed); a parameter / type-parameter name keeps its modifiers
        // (`val`/`var`/`vararg`/`reified`), so keyword context stays on there.
        val declName = declarationNameKind(markerLeaf)
        if (declName != DeclNameKind.NONE) {
            return PositionResult(emptyList(), extra = extra, keywordContext = declName == DeclNameKind.PARAM)
        }
        // The type the context wants → offer literals/enum constants and rank assignable candidates first.
        val expected = resolver.expectedTypeAt(offset)
        expected?.let { extra += expectedExtras(it, matcher, autoImport) }
        val raw = KotlinPerf.span("scope") { resolver.scopeSymbolsAt(offset, prefix) } +
                KotlinPerf.span("typeNames") { service.typeNamesByPrefix(prefix) }
        return PositionResult(raw, extra = extra, expected = expected, keywordContext = true)
    }

    /** Whether the caret sits on the NAME identifier of a declaration, and if so which kind — so completion can
     *  suppress the scope/type candidates that never belong where the user is inventing an identifier. */
    private enum class DeclNameKind { NONE, DECL, PARAM }

    /** Classify [leaf] as a declaration-name spot: [DeclNameKind.DECL] for a value/function/class/object/
     *  typealias/destructuring-entry name (no keywords either), [DeclNameKind.PARAM] for a value- or
     *  type-parameter name (keeps its modifier keywords), else [DeclNameKind.NONE]. Keys on the leaf actually
     *  being the declaration's `nameIdentifier`, so a reference or initializer/type slot is never misread. */
    private fun declarationNameKind(leaf: PsiElement?): DeclNameKind {
        val decl = leaf?.parent as? KtNamedDeclaration ?: return DeclNameKind.NONE
        if (decl.nameIdentifier !== leaf) return DeclNameKind.NONE
        return if (decl is KtParameter || decl is KtTypeParameter) DeclNameKind.PARAM else DeclNameKind.DECL
    }

    // --- override / named-argument / expected-type extras ---

    /** True at a member-declaration spot inside a class body (not within a function body, initializer,
     *  parameter list, supertype list, or type) — where `override` members should be offered. */
    private fun isOverridePosition(leaf: PsiElement?): Boolean {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtClassBody -> return true
                is KtBlockExpression, is KtCallExpression, is KtValueArgumentList, is KtPropertyAccessor,
                is KtParameterList, is KtSuperTypeList, is KtTypeReference, is KtImportDirective, is KtPackageDirective ->
                    return false

                is KtProperty -> if (prev != null && prev === n.initializer) return false
                is KtNamedFunction -> if (prev != null && prev === n.bodyExpression) return false
            }
            prev = n
            n = n.parent
        }
        return false
    }

    /** True at a name/modifier spot in a PRIMARY constructor's parameter list (not inside a parameter's type
     *  reference or default value) — where `override val`/`var` property stubs belong. */
    private fun isPrimaryCtorParamPosition(leaf: PsiElement?): Boolean {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtTypeReference, is KtValueArgumentList -> return false
                is KtParameter -> if (prev != null && prev === n.defaultValue) return false
                is KtParameterList -> return n.parent is KtPrimaryConstructor
            }
            prev = n
            n = n.parent
        }
        return false
    }

    /** The call whose argument list contains [leaf] (for named-argument completion), or null. */
    private fun namedArgCallOf(leaf: PsiElement?): KtCallExpression? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return list.parent as? KtCallExpression
    }

    private fun suppliedArgNames(call: KtCallExpression): Set<String> =
        call.valueArguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()

    private val previewAnnotationNames =
        setOf("Preview") + PreviewConstants.builtinMultiPreviews.keys

    /**
     * Completion inside ANY annotation's argument list. At a name position, the annotation's not-yet-supplied
     * parameter names (resolved from its type, ranked first as named-argument items); for `@Preview` (or a
     * MultiPreview) they fall back to the bundled argument list when the androidx type isn't on the resolved
     * classpath. At the value of `@Preview`'s `uiMode`/`device`, the `Configuration.UI_MODE_*` / `Devices.*`
     * constants the argument accepts (not recoverable from the `Int`/`String` parameter type alone). Empty
     * outside an annotation.
     */
    private fun annotationArgExtras(
        leaf: PsiElement?,
        matcher: PrefixMatcher,
        resolver: KotlinResolver
    ): List<CompletionItem> {
        val arg = climbTo<KtValueArgument>(leaf) ?: return emptyList()
        val list = arg.parent as? KtValueArgumentList ?: return emptyList()
        val ann = list.parent as? KtAnnotationEntry ?: return emptyList()
        val short = ann.shortName?.asString()
        val out = ArrayList<CompletionItem>()
        val argName = arg.getArgumentName()?.asName?.identifier
        val editingName = climbTo<KtValueArgumentName>(leaf) != null
        // Value position of a known @Preview constant-typed argument (the marker sits in the value, not the name).
        if (argName != null && !editingName && short in previewAnnotationNames) {
            fun constItem(qualifier: String, name: String, detail: String) {
                val insert = "$qualifier.$name"
                out += CompletionItem(
                    insert,
                    insert,
                    CompletionItemKind.FIELD,
                    detail = detail,
                    sortPriority = -1
                )
            }
            when (argName) {
                "uiMode" -> PreviewConstants.uiModeConstants.keys
                    .filter { matcher.matches(it) }
                    .forEach { constItem("Configuration", it, "uiMode") }

                "device" -> PreviewConstants.deviceConstants.keys
                    .filter { matcher.matches(it) }.forEach { constItem("Devices", it, "device") }
            }
        }
        // Name position (a bare argument, or editing an argument's name) → the annotation's parameter names.
        if (argName == null || editingName) {
            val supplied =
                list.arguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()
            val params = resolver.annotationParameters(ann)
                .filter { it.name !in supplied && matcher.matches(it.name) }
            if (params.isNotEmpty()) {
                params.forEach {
                    out += KotlinCompletionItems.namedArgItem(
                        it,
                        bareName = editingName
                    )
                }
            } else if (short in previewAnnotationNames) {
                // The androidx @Preview type isn't on the resolved classpath (or the index is still building) —
                // fall back to the bundled argument list so @Preview completion works regardless. Same item shape
                // as a resolved named argument (label `name =`, inserts `name = `).
                PreviewConstants.previewArgNames
                    .filter { it !in supplied && matcher.matches(it) }
                    .forEach {
                        out += CompletionItem(
                            "$it =",
                            "$it = ",
                            CompletionItemKind.PARAMETER,
                            detail = "@Preview",
                            sortPriority = -1
                        )
                    }
            }
        }
        return out
    }

    /** Literals/constants the expected type admits: `true`/`false` for Boolean, `Enum.CONSTANT` for an enum,
     *  and the type's own companion constants (`Color.Transparent`, `Alignment.Center`) — each with an
     *  auto-import of the type when it isn't already visible. Powers value completion after `param = `. */
    private fun expectedExtras(
        expected: KotlinType,
        matcher: PrefixMatcher,
        autoImport: KotlinAutoImport
    ): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()
        val simple = expected.qualifiedName.substringAfterLast('.')
        // These constants ARE the expected type, so they carry the fits-expected relevance the weigher boosts.
        val fits = CompletionRelevance(fitsExpectedType = true)
        fun constItem(c: KotlinSymbol, kind: CompletionItemKind) {
            val insert = "$simple.${c.name}"
            out += CompletionItem(
                label = insert,
                insertText = insert,
                kind = kind,
                detail = simple,
                sortPriority = -1,
                symbol = c,
                additionalEdits = autoImport.editForType(expected.qualifiedName),
                relevance = fits,
            )
        }
        if (expected.qualifiedName == "kotlin.Boolean") {
            for (b in listOf("true", "false")) {
                if (matcher.matches(b)) {
                    out += CompletionItem(
                        b,
                        b,
                        CompletionItemKind.KEYWORD,
                        sortPriority = -1,
                        relevance = fits
                    )
                }
            }
        }
        val consts = service.enumConstantsOf(expected.qualifiedName)
        if (consts.isNotEmpty()) {
            consts.filter { matcher.matches(it.name) }
                .forEach { constItem(it, CompletionItemKind.ENUM_CONSTANT) }
        } else {
            // Not an enum — offer the type's companion constants OF that type (`val Transparent: Color` on
            // `Color`'s companion), the value-class / object-constant idiom. Capped so a large palette (Color
            // has dozens) doesn't flood the popup; prefix-filtering narrows it as the user types.
            service.companionMembersFor(expected.qualifiedName, matcher.prefix)
                .filter { it.kind == SymbolKind.FIELD && (it.type as? KotlinType)?.qualifiedName == expected.qualifiedName }
                .take(MAX_EXPECTED_CONSTANTS)
                .forEach { constItem(it, CompletionItemKind.FIELD) }
        }
        return out
    }

    // --- ranking ---

    /** A raw candidate with its rank facts computed ONCE (not per comparison): the same facts ride the
     *  emitted item as [CompletionRelevance] so the engine's weigher chain reproduces this order over the
     *  merged, multi-contributor set. */
    private class Candidate(
        val symbol: KotlinSymbol,
        val importEdit: List<TextEdit>,
        val fitsExpected: Boolean,
        val grade: Int,
        val boosted: Boolean,
        val group: Int,
    ) {
        fun relevance() = CompletionRelevance(
            fitsExpectedType = fitsExpected,
            contextBoost = boosted,
            callableWeight = group,
            inScope = importEdit.isEmpty(),
            deprecated = symbol.isDeprecated,
            proximity = KotlinCompletionItems.proximity(symbol.kind),
        )
    }

    private fun rank(): Comparator<Candidate> = compareBy(
        { if (it.fitsExpected) 0 else 1 },                         // expected-type matches first
        { it.grade },                                              // exact > cs-prefix > ci-prefix > hump > substring
        { if (it.boosted) 0 else 1 },                              // @Composable callables first in a @Composable context
        { it.group },                                              // own members > source ext > library ext > universal scope fns > Object methods
        { if (it.importEdit.isEmpty()) 0 else 1 },                 // in-scope (no import) before needs-import
        { KotlinCompletionItems.proximity(it.symbol.kind) },       // locals/members before library
        { it.symbol.name.length },
        { it.symbol.name },
    )

    /**
     * IntelliJ-style grouping for member-access ranking (lower sorts earlier): the receiver's own members
     * first, then project-source extensions, then other (library) extensions on a specific receiver, then the
     * ubiquitous universal scope functions (`let`/`run`/`also`/`apply`/`takeIf`/`to`, … — declared on an
     * unbounded type parameter / `kotlin.Any`, so they apply to every receiver), with the `Object` methods
     * (`equals`/`hashCode`/`toString`) last. In name-reference position every candidate is a non-extension,
     * so all share group 0 and this key is inert there — it only re-orders the `receiver.` member list, where
     * extensions used to interleave with (and routinely precede) real members purely on the shorter name.
     */
    private fun memberGroup(s: KotlinSymbol): Int = when {
        s.name in OBJECT_METHODS -> 4
        !s.isExtension -> 0
        s.receiverTypeParam != null || s.receiverTypeFqn == "kotlin.Any" -> 3 // universal scope function (T./Any.)
        s.origin.fromSource -> 1                                              // project-source extension
        else -> 2                                                             // library extension on a specific receiver
    }

    /** Whether a candidate's (return/declared) type is assignable to the [expected] type — boosts it in [rank]. */
    private fun matchesExpected(s: KotlinSymbol, expected: KotlinType): Boolean {
        val t = s.type as? KotlinType ?: return false
        if (t.qualifiedName == expected.qualifiedName) return true
        return runCatching { expected.isAssignableFrom(t) }.getOrDefault(false)
    }

    // --- auto-import (symbol -> FQN; the visibility/anchor work lives in KotlinAutoImport) ---

    private fun importEditFor(s: KotlinSymbol, autoImport: KotlinAutoImport): List<TextEdit> {
        val fqn = when {
            s.kind in TYPE_KINDS -> (s.type as? KotlinType)?.qualifiedName
            // A top-level callable (println, listOf, ln, …) or top-level extension (`Int.dp`, `String.trim`) is
            // imported by its own FQN `package.name` — Kotlin imports the callable, not a facade class. `ln`/`PI`
            // from kotlin.math and `dp`/`sp` from compose.ui.unit aren't default-imported, so they need an import
            // on accept. A *member* extension (declared inside a class/scope, like Compose's `RowScope.weight`) is
            // NOT importable that way — its dispatch receiver must already be in scope — so it gets no import;
            // [isTopLevelCallable] keeps it out.
            s.packageName != null && (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) &&
                    (!s.isExtension || isTopLevelCallable(s)) ->
                "${s.packageName}.${s.name}"

            else -> null
        } ?: return emptyList()
        return autoImport.editForType(fqn)
    }

    /**
     * Whether [s] is a file-level (top-level) callable — importable by its own `package.name` FQN. A top-level
     * callable compiles into a Kotlin file facade class (`…Kt` by convention; a multi-file facade keeps the
     * suffix too); a *member* (incl. a member extension like `RowScope.weight`) has a real declaring type. A
     * source extension carries no declaring class. A facade renamed via `@file:JvmName` is conservatively
     * treated as non-top-level — the safe failure is a missed auto-import, never a broken one.
     */
    private fun isTopLevelCallable(s: KotlinSymbol): Boolean {
        val declaring = s.declaringClassFqn ?: return true
        return declaring.substringAfterLast('.').endsWith("Kt")
    }

    // --- position predicates / leaf utilities ---

    /** Instance receiver → non-static members (+ extensions); type receiver → statics + nested types. */
    private fun memberVisibleOn(s: KotlinSymbol, typeReceiver: Boolean): Boolean {
        if (s.kind == SymbolKind.CONSTRUCTOR) return false // never reached via `.`
        if (Modifier.PRIVATE in s.modifiers) return false // private members aren't accessible via an explicit `.`
        if (s.isInternal && !s.origin.fromSource) return false // a library's `internal` isn't accessible cross-module
        val isStatic = Modifier.STATIC in s.modifiers
        return if (typeReceiver) isStatic || s.kind in TYPE_KINDS else !isStatic
    }

    /** Inside the literal text of a string (suppress completion), but NOT inside a `${ }`/`$name` entry. */
    private fun insideStringLiteral(leaf: PsiElement?): Boolean {
        climbTo<KtStringTemplateExpression>(leaf) ?: return false
        val entry = climbTo<KtStringTemplateEntry>(leaf)
        return !(entry is KtBlockStringTemplateEntry || entry is KtSimpleNameStringTemplateEntry)
    }

    /** The dotted path of a receiver that is a pure name/qualified-name chain (`java`, `java.util`), for
     *  package completion. Null for anything else (a call, literal, indexing — not a package prefix). */
    private fun packagePathOf(expr: KtExpression): String? = when (expr) {
        is KtNameReferenceExpression -> expr.getReferencedName()
        is KtQualifiedExpression -> {
            val r = packagePathOf(expr.receiverExpression)
            val s = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (r != null && s != null) "$r.$s" else null
        }

        else -> null
    }

    private fun isSelectorOfQualified(nameRef: KtNameReferenceExpression): Boolean {
        val q = nameRef.parent as? KtQualifiedExpression ?: return false
        return q.selectorExpression === nameRef
    }

    private fun inTypePosition(leaf: PsiElement?): Boolean = climbTo<KtTypeReference>(leaf) != null

    private companion object {
        // The classic completion dummy identifier; unlikely to collide with real code.
        const val MARKER = "IntellijIdeaRulezzz"

        // Matches the engine's ranked cap (CompletionEngine.MAX_ITEMS) so this rank-aware inner cut never
        // drops a candidate the engine's global ranking would have kept — the engine cap is authoritative.
        const val MAX_ITEMS = 200

        // Cap on a type's own companion constants offered at an expected-type slot (`Color` has dozens of
        // named colors) so the popup stays readable; the typed prefix narrows them further.
        const val MAX_EXPECTED_CONSTANTS = 12
        val TYPE_KINDS = setOf(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.ENUM,
            SymbolKind.ANNOTATION_TYPE,
            SymbolKind.RECORD,
        )

        // The universal `Object`/`Any` methods — always present on every receiver, so they sort to the bottom
        // of a member-access list (IntelliJ does the same) rather than competing with the type's real members.
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")
    }
}
