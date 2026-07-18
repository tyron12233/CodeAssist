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
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
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
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

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
            name, spliced
        )
        splicedPath = path
        splicedTree = kt
        return kt
    }

    override suspend fun fillCompletionVariants(
        params: CompletionParams, result: CompletionResultSet
    ) {
        val res = KotlinPerf.trace("kt.complete") { completeInner(params.document, params.offset) }
        result.addAllElements(res.items)
        result.setReplacementRange(res.replacementRange)
        if (res.isIncomplete) result.markIncomplete()
    }

    private fun completeInner(
        document: dev.ide.lang.incremental.DocumentSnapshot, requestedOffset: Int
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
                document.file.name, document.file.path, spliced
            )
        }
        val parsed = KotlinParsedFile(kt, document.file, document.version)
        // Same-file freshness: a class/member declared in THIS buffer (`with(LocalClass()) { … }`) resolves from
        // the live PSI. Keyed by the marker-free text hash so it shares the focal entry analyze/highlight set
        // (a no-op when already synced); the marker sits at the caret, leaving referenced declarations intact.
        runCatching {
            service.syncFocal(document.file.path, original.hashCode()) {
                dev.ide.lang.kotlin.symbols.SourceIndexBuilder.extractFrom(
                    kt, parsed, document.file.path
                )
            }
        }
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        if (insideStringLiteral(markerLeaf)) {
            return CompletionResult(
                emptyList(), isIncomplete = false, replacementRange = replaceRange
            )
        }
        // Inside a KDoc `/** */`: offer doc tags (`@param`, `@return`, …) / the documented declaration's
        // parameter names after `@param`, and otherwise NOTHING — never the scope symbols/keywords a plain
        // name-ref classification would wrongly leak into a doc comment.
        kdocCompletion(markerLeaf, original, offset, prefix, matcher)?.let { kdocItems ->
            return CompletionResult(kdocItems, isIncomplete = false, replacementRange = replaceRange)
        }
        // A label reference (`return@<caret>`, `break@`/`continue@<caret>`, `this@`/`super@<caret>`): offer the
        // enclosing labels, NOT scope locals + type names — which is what a plain name-ref classification would
        // wrongly surface here (a jump target is never a local or a type).
        labelCompletion(markerLeaf, matcher)?.let { labelItems ->
            return CompletionResult(labelItems, isIncomplete = false, replacementRange = replaceRange)
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
                    where.receiver, offset, resolver, matcher, where.callableRef
                )
                // A type slot also carries a few keyword positions (a `where` clause after a generic
                // signature); KotlinKeywords returns nothing for a plain type reference, so this is safe.
                CompletionPosition.TypeReference -> service.typeNameCandidates(prefix).let {
                    // At an annotation NAME (`@Comp…`) only annotation classes belong — restrict to them so the
                    // popup isn't every classifier (IntelliJ's `@` filter). Non-annotation position → all types.
                    val syms = if (inAnnotationNamePosition(markerLeaf))
                        it.symbols.filter { s -> s.kind == SymbolKind.ANNOTATION_TYPE } else it.symbols
                    // `when (subject) { is <caret> }` on a SEALED subject → surface its subclasses. They're
                    // assignable to the subject type, so passing it as `expected` floats them above every other
                    // classifier (the rank's fits-expected key); adding them explicitly guarantees they appear
                    // even past the type-name cap. A non-sealed / subjectless `when` keeps the plain type list.
                    val sealedSubject = sealedWhenSubject(markerLeaf, resolver)
                    if (sealedSubject != null) {
                        val subs = service.sealedSubtypeCandidates(sealedSubject.qualifiedName, prefix).orEmpty()
                        PositionResult(subs + syms, expected = sealedSubject, keywordContext = true, capped = it.capped)
                    } else PositionResult(syms, keywordContext = true, capped = it.capped)
                }

                is CompletionPosition.InfixName -> infixCandidates(
                    where.left, offset, resolver, matcher
                )

                CompletionPosition.NameReference -> nameReferenceCandidates(
                    markerLeaf, offset, matcher, resolver, autoImport
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
            val packageCompletion = pos.packageCompletion
            val expected = pos.expected
            val nameReference = pos.nameReference
            val out = ArrayList<Candidate>(raw.size)
            val seen = HashSet<String>()
            for (s in raw) {
                val name = s.name
                if (name == "_" || MARKER in name) continue
                val grade = matcher.grade(name) ?: continue
                if (!seen.add(name + "#" + s.kind + "#" + (s.signature ?: ""))) continue
                out += Candidate(
                    symbol = s,
                    importEdit = if (packageCompletion || (pos.memberAccess && s.kind in TYPE_KINDS)) emptyList()
                    else importEditFor(s, autoImport),
                    fitsExpected = expected != null && matchesExpected(s, expected),
                    grade = grade.ordinal,
                    boosted = composableContext && s.isComposable,
                    group = memberGroup(s),
                    proximity = proximityOf(s, nameReference),
                )
            }
            out.sortWith(RANK)
            if (out.size > MAX_ITEMS) out.subList(MAX_ITEMS, out.size).clear()
            out
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
        val items =
            ((pos.extra.distinctBy { it.kind to it.label } + symbolItems).take(keep) + tail).distinctBy { it.kind to it.label }
                .take(MAX_ITEMS)
        return CompletionResult(
            items = items,
            // Incomplete when the final set was truncated OR a producer capped its query — either way matches
            // exist that this page doesn't hold, so the engine must re-query as the prefix narrows instead of
            // narrowing this page client-side (which would permanently hide, e.g., `StringBuilder` typed from `S`).
            // Also incomplete while the classpath index is still BUILDING: this page was served from whatever
            // segments are open so far, so a classpath type (`Modifier`, …) that indexes moments later is absent
            // — force a re-query on each keystroke until the index is ready, otherwise the editor caches this
            // pre-index page as complete and a fast typist never sees the type appear (cf. LearnBackend).
            isIncomplete = raw.size > MAX_ITEMS || pos.capped || !service.classpathReady(),
            replacementRange = replaceRange,
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
        /** A bare name-reference position (scope symbols ∪ visible types), where a top-level LIBRARY callable
         *  is no nearer than a library type and so must not outrank a classifier on the proximity key (see
         *  [proximityOf]). Off for member access / infix, whose candidates are members ranked by callableWeight. */
        val nameReference: Boolean = false,
        /** A candidate producer (the classpath type-name index) truncated its result at the query cap, so more
         *  matches exist than [raw] holds. Forces the result incomplete so the engine re-queries as the prefix
         *  narrows rather than client-side-narrowing a capped page (see [KotlinSymbolService.typeNameCandidates]). */
        val capped: Boolean = false,
        /** A `receiver.` member-access position: a nested TYPE candidate (`GridCells.Fixed`) is already reached
         *  through its qualifier, so it needs NO auto-import (unlike a bare type-reference or a top-level
         *  extension, which do). Gates the import-edit computation below. */
        val memberAccess: Boolean = false,
    )

    private fun classifyPosition(
        markerLeaf: PsiElement?, nameRef: KtNameReferenceExpression?
    ): CompletionPosition {
        // A callable reference `Receiver::name` (`String::length`, `foo::bar`) — the name after `::` completes
        // from the receiver's members, exactly like a `.` access (bound/unbound is the same candidate set).
        val callableRefReceiver =
            nameRef?.let { (it.parent as? KtCallableReferenceExpression)?.takeIf { p -> p.callableReference === it }?.receiverExpression }
        // The receiver of a `recv.name` / `recv.name(args)` / `recv.name { }` member access at the marker — see
        // [qualifiedMemberReceiver] for the call-selector (trailing-lambda / argument-list) case.
        val memberReceiver = nameRef?.let { qualifiedMemberReceiver(it) }
        return when {
            callableRefReceiver != null -> CompletionPosition.MemberAccess(
                callableRefReceiver, callableRef = true
            )

            memberReceiver != null -> CompletionPosition.MemberAccess(memberReceiver)

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
            resolver.inferType(receiver)
                ?.let { resolver.receiverForMembers(it, receiver.textRange.startOffset) }
        }
        if (recvType != null) {
            val typeReceiver = resolver.isTypeReceiver(receiver)
            val members = KotlinPerf.span("members") {
                service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
            }.filter { callableRef || memberVisibleOn(it, typeReceiver) }
            // A bare `Type.` where the type has a companion object resolves to the companion instance, so the
            // companion's own members (Compose's `Color.Black`/`White`) and the extensions applicable to it
            // (`Modifier.Companion : Modifier` → `Modifier.padding`/`background`) are in scope too
            val raw = if (typeReceiver) {
                // An enum's CONSTANTS are reached statically (`Test.A`) but aren't instance members, so
                // `membersForCompletion` (which surfaces `values()`/`valueOf()`/`entries`) never lists them.
                val enumConstants = service.enumConstantsOf(recvType.qualifiedName)
                    .filter { matcher.matches(it.name) }
                // The companion object itself (`Test.Companion`), reached statically through the type — offered
                // alongside its members (`Test.rainbowColors`). Named companions appear under their given name.
                val companion = service.companionObjectSymbol(recvType.qualifiedName)
                    ?.takeIf { matcher.matches(it.name) }
                members + enumConstants + listOfNotNull(companion) + service.companionMembersFor(
                    recvType.qualifiedName, prefix
                ).filter { memberVisibleOn(it, typeReceiver = false) }
            } else {
                // Member-extensions in scope on an instance receiver (`map.printMap()` where `printMap` is a
                // `Map<…>` extension declared in the enclosing class, `Modifier.weight` inside a `Row { }`).
                members + resolver.scopeMemberExtensions(offset, recvType, prefix)
            }
            return PositionResult(raw, memberAccess = true)
        }
        // Receiver is a package/FQN prefix (`java.util.`, `android.`) — complete its sub-packages + the types in
        // it (inserted fully-qualified, so it needs no auto-import).
        val pkg = packagePathOf(receiver)
        return if (pkg != null) PositionResult(
            service.packageMembers(pkg, prefix), packageCompletion = true
        )
        else PositionResult(emptyList())
    }

    /** `left infixName█` — the infix functions applicable to [left]'s type: its own members plus the extensions
     *  on it (`0 downTo`, `range step`, a user `infix fun`), each filtered to `infix`. The `as`/`in`/`is`
     *  keyword operators that also fit the slot come from KotlinKeywords (keywordContext), so a null-typed or
     *  unresolved left operand still offers those. Scope symbols are NOT offered — an operation reference only
     *  accepts an infix function name, never an arbitrary local. */
    private fun infixCandidates(
        left: KtExpression, offset: Int, resolver: KotlinResolver, matcher: PrefixMatcher
    ): PositionResult {
        val prefix = matcher.prefix
        val recvType = KotlinPerf.span("infer") {
            resolver.inferType(left)
                ?.let { resolver.receiverForMembers(it, left.textRange.startOffset) }
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
        // A bare `import <caret>` (no dot yet): offer the top-level package roots (`androidx`, `kotlin`, …), NOT
        // scope symbols / types. `packageCompletion = true` inserts the bare segment with NO auto-import edit —
        // otherwise accepting a type here injected a second `import` statement while editing an import. (A dotted
        // `import a.b.<caret>` is a member-access position, already served by packageMembers.)
        if (climbTo<KtImportDirective>(markerLeaf) != null || climbTo<KtPackageDirective>(markerLeaf) != null) {
            return PositionResult(service.rootPackages(prefix), packageCompletion = true)
        }
        val extra = ArrayList<CompletionItem>()
        // Member-declaration position in a class body → offer overridable inherited members as stubs. The stub
        // carries the member line's indent (so its body lines up) and the range of any `override`/`fun`/`val`
        // already typed before the name (so accepting REPLACES it rather than duplicating `override fun`).
        if (isOverridePosition(markerLeaf)) {
            val (indent, leadingDelete) = overrideInsertContext(markerLeaf, offset)
            resolver.overridableMembersAt(offset).filter { matcher.matches(it.name) }
                .forEach { extra += KotlinCompletionItems.overrideItem(it, indent, leadingDelete) }
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
        // Inside a constructor-DELEGATION call the KtCallExpression path above can't see: a supertype call in a
        // class header (`class X : Base(<caret>)`) or a secondary ctor's `: this(<caret>)` / `: super(<caret>)`.
        // The target constructor's parameters resolve to named-arg offers exactly as a normal call's do.
        delegationArgListOf(markerLeaf)?.let { list ->
            val editingName = climbTo<KtValueArgumentName>(markerLeaf) != null
            val supplied = list.arguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()
            resolver.delegationCallParameters(markerLeaf)
                .filter { it.name !in supplied && matcher.matches(it.name) }
                .forEach { extra += KotlinCompletionItems.namedArgItem(it, bareName = editingName) }
        }
        // Inside an annotation's argument list → its parameter names (ranked first), plus the enum-ish constants
        // for @Preview's uiMode/device which aren't recoverable from the parameter type alone.
        extra += annotationArgExtras(markerLeaf, matcher, resolver)
        // At a `when (subject) { <caret> }` branch slot on a SEALED/ENUM subject → a single "add remaining
        // branches" item that fills every not-yet-covered case (IntelliJ's exhaustive-when fill).
        whenAddBranchesItem(markerLeaf, offset, resolver)?.let { extra += it }
        // The marker IS the NAME being given to a declaration (`val foo`, `fun bar`, a parameter) — an
        // identifier the user is inventing, so scope symbols / type names / auto-imports don't belong here.
        // (Any override / ctor-property / named-argument stubs above still apply: they're the *right* offers at
        // a member/ctor-param name spot.) A value/function/class/typealias name admits no keywords either (the
        // declaring keyword is already typed); a parameter / type-parameter name keeps its modifiers
        // (`val`/`var`/`vararg`/`reified`), so keyword context stays on there.
        val declName = declarationNameKind(markerLeaf)
        if (declName != DeclNameKind.NONE) {
            return PositionResult(
                emptyList(), extra = extra, keywordContext = declName == DeclNameKind.PARAM
            )
        }
        // The type the context wants → offer literals/enum constants and rank assignable candidates first.
        val expected = resolver.expectedTypeAt(offset)
        expected?.let { extra += expectedExtras(it, matcher, autoImport) }
        val types = KotlinPerf.span("typeNames") { service.typeNameCandidates(prefix) }
        val raw =
            KotlinPerf.span("scope") { resolver.scopeSymbolsAt(offset, prefix) } + types.symbols
        return PositionResult(
            raw, extra = extra, expected = expected, keywordContext = true,
            nameReference = true, capped = types.capped,
        )
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

    /**
     * The insert context for an override stub at [leaf]: the member line's leading indentation (prepended to
     * the stub's continuation lines so a nested member's body/`}` align to its column), and — when [leaf] is
     * the NAME of a declaration the user has already begun (`override fun onCr|`) — the range of the
     * `override`/`fun`/`val` + modifiers before the name, so accepting the stub REPLACES that text instead of
     * inserting a duplicate `override fun` next to it. Indentation is read from the (marker-spliced) buffer;
     * the marker sits at the caret, so the line's leading whitespace is intact.
     */
    private fun overrideInsertContext(leaf: PsiElement?, offset: Int): Pair<String, TextRange?> {
        val text = leaf?.containingFile?.text ?: return "" to null
        var lineStart = offset.coerceIn(0, text.length)
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        val indent = text.substring(lineStart, i)
        val decl = leaf.parent as? KtNamedDeclaration
        val leadingDelete = if (decl != null && decl.nameIdentifier === leaf) {
            val nameStart = leaf.textRange.startOffset
            // Delete from the `override`/`fun`/`val`/`var` keyword (NOT the declaration's textRange start, which
            // would also swallow a leading annotation like `@Composable`) up to the partial name.
            val kwStart = declKeywordStart(decl)
            if (kwStart != null && kwStart < nameStart) TextRange(kwStart, nameStart) else null
        } else null
        return indent to leadingDelete
    }

    /** The earliest offset of the `override` modifier or the `fun`/`val`/`var` keyword of [decl] — where the
     *  stub-replacing deletion should begin, leaving any preceding annotations untouched. Null when none is
     *  present (a bare name with no keywords typed yet → nothing to replace). */
    private fun declKeywordStart(decl: KtNamedDeclaration): Int? {
        val offsets = ArrayList<Int>(2)
        decl.modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD)?.textRange?.startOffset?.let { offsets += it }
        when (decl) {
            is KtNamedFunction -> decl.funKeyword?.textRange?.startOffset?.let { offsets += it }
            is KtProperty -> decl.valOrVarKeyword?.textRange?.startOffset?.let { offsets += it }
            else -> {}
        }
        return offsets.minOrNull()
    }

    /** True at a member-declaration spot inside a class body (not within a function body, initializer,
     *  parameter list, supertype list, or type) — where `override` members should be offered. */
    private fun isOverridePosition(leaf: PsiElement?): Boolean {
        var prev: PsiElement? = null
        var n: PsiElement? = leaf
        while (n != null) {
            when (n) {
                is KtClassBody -> return true
                is KtBlockExpression, is KtCallExpression, is KtValueArgumentList, is KtPropertyAccessor, is KtParameterList, is KtSuperTypeList, is KtTypeReference, is KtImportDirective, is KtPackageDirective -> return false

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

    /** A single "add remaining branches" item at a `when (subject) { <caret> }` branch slot when the subject is
     *  a SEALED type or an enum and some cases aren't covered yet — inserts one branch per missing case
     *  (`is Circle -> TODO()` / `Color.RED -> TODO()`), indented to the branch column. Null otherwise (not a
     *  branch-condition slot, an unresolved/non-exhaustible subject, or every case already covered). */
    private fun whenAddBranchesItem(leaf: PsiElement?, offset: Int, resolver: KotlinResolver): CompletionItem? {
        climbTo<KtWhenConditionWithExpression>(leaf) ?: return null // a branch-condition slot (not a branch body)
        val whenExpr = climbTo<KtWhenExpression>(leaf) ?: return null
        val subject = whenExpr.subjectExpression ?: return null
        val fqn = (resolver.inferType(subject) ?: return null).qualifiedName
        val covered = HashSet<String>()
        for (entry in whenExpr.entries) for (cond in entry.conditions) when (cond) {
            is KtWhenConditionIsPattern ->
                cond.typeReference?.text?.substringBefore('<')?.substringAfterLast('.')?.trim()?.let { covered += it }
            is KtWhenConditionWithExpression ->
                cond.expression?.text?.substringAfterLast('.')?.trim()?.let { if (MARKER !in it) covered += it }
        }
        val branches = service.sealedSubclassesOf(fqn)?.let { subs ->
            subs.map { it.substringAfterLast('.') }.filter { it !in covered }.map { "is $it -> TODO()" }
        } ?: service.enumConstantsOf(fqn).map { it.name }.filter { it !in covered }.let { missing ->
            val simple = fqn.substringAfterLast('.')
            missing.map { "$simple.$it -> TODO()" }
        }
        if (branches.isEmpty()) return null
        val indent = lineIndentAt(leaf?.containingFile?.text ?: "", offset)
        return CompletionItem(
            label = "Add remaining branches",
            insertText = branches.joinToString("\n$indent"),
            kind = CompletionItemKind.KEYWORD,
            detail = "when",
            sortPriority = -3,
        )
    }

    /** The leading whitespace of the line containing [offset] in [text]. */
    private fun lineIndentAt(text: CharSequence, offset: Int): String {
        var start = offset.coerceIn(0, text.length)
        while (start > 0 && text[start - 1] != '\n') start--
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(start, i)
    }

    /** Completion inside a KDoc comment, or null when [leaf] isn't in one. `@param <caret>` → the documented
     *  declaration's parameter names; `@<caret>` → the standard doc tags; anywhere else in the doc → an EMPTY
     *  list (suppresses the scope/keyword completion that would otherwise leak into prose). */
    private fun kdocCompletion(
        leaf: PsiElement?, original: String, offset: Int, prefix: String, matcher: PrefixMatcher
    ): List<CompletionItem>? {
        climbTo<KDoc>(leaf) ?: return null
        val lineStart = original.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val linePrefix = original.substring(lineStart, offset)
        if (KDOC_PARAM_RE.containsMatchIn(linePrefix)) {
            val params = climbTo<KtNamedFunction>(leaf)?.valueParameters?.mapNotNull { it.name }
                ?: climbTo<KtClassOrObject>(leaf)?.primaryConstructorParameters?.mapNotNull { it.name }
                ?: emptyList()
            return params.filter { matcher.matches(it) }
                .map { CompletionItem(it, it, CompletionItemKind.FIELD, sortPriority = -1) }
        }
        // A tag only right after `@` (the `@` is already typed, so insert the name without it, space-terminated).
        if (original.getOrNull(offset - prefix.length - 1) == '@') {
            return KDOC_TAGS.filter { matcher.matches(it) }
                .map { CompletionItem("@$it", "$it ", CompletionItemKind.KEYWORD, sortPriority = -1) }
        }
        return emptyList()
    }

    /** Label candidates at a label reference [leaf] (`@<caret>` in return/break/continue/this/super), or null
     *  when the caret isn't at a label. A jump's valid targets differ by kind: `break`/`continue` take only
     *  labeled LOOPS; `return` takes enclosing lambda implicit labels (the call's name — `forEach`, `let`,
     *  `Composable`), explicit `label@` names, and enclosing function names; `this`/`super` take enclosing class
     *  names + receiver-lambda labels. */
    private fun labelCompletion(leaf: PsiElement?, matcher: PrefixMatcher): List<CompletionItem>? {
        if (climbTo<KtLabelReferenceExpression>(leaf) == null) return null
        val labels = LinkedHashSet<String>()
        when {
            climbTo<KtBreakExpression>(leaf) != null || climbTo<KtContinueExpression>(leaf) != null ->
                forEachAncestor(leaf) { n -> if (n is KtLoopExpression) (n.parent as? KtLabeledExpression)?.getLabelName()?.let { labels += it } }

            climbTo<KtThisExpression>(leaf) != null || climbTo<KtSuperExpression>(leaf) != null ->
                forEachAncestor(leaf) { n ->
                    when (n) {
                        is KtClassOrObject -> n.name?.let { labels += it }
                        is KtLambdaExpression -> lambdaImplicitLabel(n)?.let { labels += it }
                        is KtLabeledExpression -> n.getLabelName()?.let { labels += it }
                    }
                }

            else -> forEachAncestor(leaf) { n -> // return@ (or a bare label)
                when (n) {
                    is KtLambdaExpression -> lambdaImplicitLabel(n)?.let { labels += it }
                    is KtLabeledExpression -> n.getLabelName()?.let { labels += it }
                    is KtNamedFunction -> n.name?.let { labels += it } // `return@funName` returns from it
                }
            }
        }
        return labels.filter { matcher.matches(it) }
            .map { CompletionItem(it, it, CompletionItemKind.KEYWORD, sortPriority = -1) }
    }

    /** The implicit label of a lambda that is a call argument — the callee's name (`list.forEach { }` →
     *  `forEach`, `run { }` → `run`), which `return@forEach` / `this@run` target. Null for a non-argument lambda. */
    private fun lambdaImplicitLabel(lambda: KtLambdaExpression): String? {
        val call = when (val p = lambda.parent) {
            is KtLambdaArgument -> p.parent as? KtCallExpression
            is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        } ?: return null
        return (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    }

    private inline fun forEachAncestor(leaf: PsiElement?, action: (PsiElement) -> Unit) {
        var n: PsiElement? = leaf
        while (n != null) { action(n); n = n.parent }
    }

    /** The call whose argument list contains [leaf] (for named-argument completion), or null. */
    private fun namedArgCallOf(leaf: PsiElement?): KtCallExpression? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return list.parent as? KtCallExpression
    }

    /** The argument list of a constructor-DELEGATION call whose IMMEDIATE arguments contain [leaf] — a
     *  supertype call (`: Base(…)`, [KtSuperTypeCallEntry]) or a secondary-ctor delegation (`: this(…)` /
     *  `: super(…)`, [KtConstructorDelegationCall]) — or null. Keys on the nearest argument's list owner, so a
     *  nested call inside a delegation argument (`: Base(foo(<caret>))`) is correctly excluded. */
    private fun delegationArgListOf(leaf: PsiElement?): KtValueArgumentList? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return if (list.parent is KtSuperTypeCallEntry || list.parent is KtConstructorDelegationCall) list else null
    }

    /** The SEALED subject type of the enclosing `when` when [leaf] sits at an `is <caret>` type pattern (the
     *  position where only the subject's subclasses are relevant), else null — not an `is` pattern, a
     *  subjectless `when`, an unresolved subject, or a subject that is not a known sealed type. */
    private fun sealedWhenSubject(leaf: PsiElement?, resolver: KotlinResolver): KotlinType? {
        climbTo<KtWhenConditionIsPattern>(leaf) ?: return null
        val whenExpr = climbTo<KtWhenExpression>(leaf) ?: return null
        val subject = whenExpr.subjectExpression ?: return null
        val t = resolver.inferType(subject) ?: return null
        return if (service.sealedSubclassesOf(t.qualifiedName) != null) t else null
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
        leaf: PsiElement?, matcher: PrefixMatcher, resolver: KotlinResolver
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
                    insert, insert, CompletionItemKind.FIELD, detail = detail, sortPriority = -1
                )
            }
            when (argName) {
                "uiMode" -> PreviewConstants.uiModeConstants.keys.filter { matcher.matches(it) }
                    .forEach { constItem("Configuration", it, "uiMode") }

                "device" -> PreviewConstants.deviceConstants.keys.filter { matcher.matches(it) }
                    .forEach { constItem("Devices", it, "device") }
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
                        it, bareName = editingName
                    )
                }
            } else if (short in previewAnnotationNames) {
                // The androidx @Preview type isn't on the resolved classpath (or the index is still building) —
                // fall back to the bundled argument list so @Preview completion works regardless. Same item shape
                // as a resolved named argument (label `name =`, inserts `name = `).
                PreviewConstants.previewArgNames.filter { it !in supplied && matcher.matches(it) }
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
        expected: KotlinType, matcher: PrefixMatcher, autoImport: KotlinAutoImport
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
        // The SHORT form of an enum constant (`RED` rather than `Color.RED`), importing the entry — what
        // IntelliJ floats first at an enum slot. Ranked above the qualified form (which stays as a no-import
        // fallback). Offered only for a true enum entry (a companion constant isn't cleanly entry-importable).
        fun shortEnumItem(c: KotlinSymbol) {
            out += CompletionItem(
                label = c.name,
                insertText = c.name,
                kind = CompletionItemKind.ENUM_CONSTANT,
                detail = simple,
                sortPriority = -2,
                symbol = c,
                additionalEdits = autoImport.editForType("${expected.qualifiedName}.${c.name}"),
                relevance = fits,
            )
        }
        // The expected type used AS a value by its own name — the companion-object idiom: `Modifier`'s bare
        // reference IS its companion object (an empty `Modifier`), so `modifier = Modifier` is valid. Android
        // Studio floats this at an expected-`Modifier` slot; offer it here (resolved from the type SHAPE, so it
        // appears even when the class-names index page capped the type out). Guarded to types whose companion
        // IS-A the expected type (so `String`, whose companion is NOT a String, isn't offered as a value).
        service.companionObjectSymbol(expected.qualifiedName)?.let { comp ->
            val compType = comp.type as? KotlinType
            if (matcher.matches(simple) && compType != null &&
                runCatching { expected.isAssignableFrom(compType) }.getOrDefault(false)
            ) {
                out += CompletionItem(
                    label = simple,
                    insertText = simple,
                    kind = CompletionItemKind.CLASS,
                    detail = simple,
                    sortPriority = -2,
                    additionalEdits = autoImport.editForType(expected.qualifiedName),
                    relevance = fits,
                )
            }
        }
        if (expected.qualifiedName == "kotlin.Boolean") {
            for (b in listOf("true", "false")) {
                if (matcher.matches(b)) {
                    out += CompletionItem(
                        b, b, CompletionItemKind.KEYWORD, sortPriority = -1, relevance = fits
                    )
                }
            }
        }
        val consts = service.enumConstantsOf(expected.qualifiedName)
        if (consts.isNotEmpty()) {
            consts.filter { matcher.matches(it.name) }
                .forEach { shortEnumItem(it); constItem(it, CompletionItemKind.ENUM_CONSTANT) }
        } else {
            // Not an enum — offer the type's companion constants OF that type (`val Transparent: Color` on
            // `Color`'s companion), the value-class / object-constant idiom. Capped so a large palette (Color
            // has dozens) doesn't flood the popup; prefix-filtering narrows it as the user types.
            service.companionMembersFor(expected.qualifiedName, matcher.prefix)
                .filter { it.kind == SymbolKind.FIELD && (it.type as? KotlinType)?.qualifiedName == expected.qualifiedName }
                .take(MAX_EXPECTED_CONSTANTS).forEach { constItem(it, CompletionItemKind.FIELD) }
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
        val proximity: Int,
    ) {
        /** No auto-import needed (already visible) — the in-scope-before-needs-import rank key, computed once. */
        val inScope: Boolean = importEdit.isEmpty()

        fun relevance() = CompletionRelevance(
            fitsExpectedType = fitsExpected,
            contextBoost = boosted,
            callableWeight = group,
            inScope = inScope,
            deprecated = symbol.isDeprecated,
            proximity = proximity,
        )
    }

    /**
     * Ranking proximity for [s]: how "near" the caret it is (locals/params closest, then members, then
     * types, then library). Delegates to [KotlinCompletionItems.proximity] except in a bare [nameReference]
     * position, where a top-level LIBRARY callable is no nearer than a library type and so is demoted BELOW
     * classifiers. Without this, the stdlib `String(stringBuilder: StringBuilder)` factory functions
     * (top-level `fun String(...)`) sort above the `String`/`StringBuilder` CLASSES purely because
     * `proximity(METHOD) < proximity(CLASS)`, burying the type the user is spelling out. Members keep their
     * near proximity (they carry no [KotlinSymbol.packageName] — only a top-level callable does), so a member
     * access / infix slot is unaffected. Kicks in only after grade ties, so a lowercase function prefix
     * (`printl` → `println`) still wins on its better match grade, never demoted under a type.
     */
    private fun proximityOf(s: KotlinSymbol, nameReference: Boolean): Int {
        val base = KotlinCompletionItems.proximity(s.kind)
        if (!nameReference) return base
        val topLevelCallable =
            (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) && s.packageName != null
        return if (topLevelCallable) TOP_LEVEL_CALLABLE_PROXIMITY else base
    }

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
            s.packageName != null && (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) && (!s.isExtension || isTopLevelCallable(
                s
            )) -> "${s.packageName}.${s.name}"

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

    /**
     * The receiver of the qualified member access whose selector is [nameRef] (the marker), or null when
     * [nameRef] isn't a member-access selector. Covers `recv.name` (the name IS the selector) AND
     * `recv.name(args)` / `recv.name { }` — where the argument list / trailing lambda makes the selector a
     * [KtCallExpression] whose callee is [nameRef], so the plain selector check misses it and completion would
     * otherwise fall through to name-reference (scope symbols + type names) instead of the receiver's members.
     */
    private fun qualifiedMemberReceiver(nameRef: KtNameReferenceExpression): KtExpression? {
        if (isSelectorOfQualified(nameRef)) return (nameRef.parent as KtQualifiedExpression).receiverExpression
        val call = nameRef.parent as? KtCallExpression ?: return null
        if (call.calleeExpression !== nameRef) return null
        val q = call.parent as? KtQualifiedExpression ?: return null
        return if (q.selectorExpression === call) q.receiverExpression else null
    }

    private fun inTypePosition(leaf: PsiElement?): Boolean = climbTo<KtTypeReference>(leaf) != null

    /** The marker sits on an annotation's NAME (`@Comp…`, `@field:Inj…`, a type-use `@…`) — a type reference
     *  under a [KtAnnotationEntry], where only annotation classes belong. Checked only in the type-reference
     *  branch (so it can't misfire on an annotation's VALUE arguments, which are a name-reference position). */
    private fun inAnnotationNamePosition(leaf: PsiElement?): Boolean = climbTo<KtAnnotationEntry>(leaf) != null

    private companion object {
        // The classic completion dummy identifier; unlikely to collide with real code.
        const val MARKER = "IntellijIdeaRulezzz"

        /** Standard KDoc block tags offered after `@` inside a `/** */` comment (names without the `@`). */
        val KDOC_TAGS = listOf(
            "param", "return", "throws", "exception", "property", "constructor", "receiver",
            "see", "sample", "since", "suppress", "author",
        )

        /** Matches a `@param ` (optionally with a partial name) at the end of the current KDoc line — the spot
         *  where the documented declaration's parameter names are offered. */
        val KDOC_PARAM_RE = Regex("@param\\s+\\w*$")

        /**
         * The candidate ordering, hand-written as one stateless comparator instead of `compareBy(8 selectors)`:
         * every key is compared as a primitive `Int` (booleans as 0/1, "true"/in-scope first) so a comparison
         * neither boxes nor walks a lambda array — the sort runs N·log N times over the candidate set, so that
         * per-compare overhead dominated the span. Key order is identical to the old `compareBy`:
         *   expected-type match → match grade → @Composable boost → member group → in-scope → proximity →
         *   name length → name. Each Candidate precomputes its keys, so this only reads fields.
         */
        val RANK = Comparator<Candidate> { a, b ->
            var c = (if (a.fitsExpected) 0 else 1) - (if (b.fitsExpected) 0 else 1)
            if (c != 0) return@Comparator c
            c = a.grade - b.grade
            if (c != 0) return@Comparator c
            c = (if (a.boosted) 0 else 1) - (if (b.boosted) 0 else 1)
            if (c != 0) return@Comparator c
            c = a.group - b.group
            if (c != 0) return@Comparator c
            c = (if (a.inScope) 0 else 1) - (if (b.inScope) 0 else 1)
            if (c != 0) return@Comparator c
            c = a.proximity - b.proximity
            if (c != 0) return@Comparator c
            val an = a.symbol.name
            val bn = b.symbol.name
            c = an.length - bn.length
            if (c != 0) return@Comparator c
            an.compareTo(bn)
        }

        // Matches the engine's ranked cap (CompletionEngine.MAX_ITEMS) so this rank-aware inner cut never
        // drops a candidate the engine's global ranking would have kept — the engine cap is authoritative.
        const val MAX_ITEMS = 200

        // Cap on a type's own companion constants offered at an expected-type slot (`Color` has dozens of
        // named colors) so the popup stays readable; the typed prefix narrows them further.
        const val MAX_EXPECTED_CONSTANTS = 12

        // Proximity a top-level LIBRARY callable gets in a name-reference position — just below the classifier
        // tier (CLASS/INTERFACE/ENUM = 3 in KotlinCompletionItems.proximity), so a type outranks a same-named
        // top-level factory function (`String` the class over the `String(...)` stdlib factories). See [proximityOf].
        const val TOP_LEVEL_CALLABLE_PROXIMITY = 4
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
