package dev.ide.lang.kotlin.completion

import dev.ide.lang.kotlin.KotlinQuickFixes
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBlockStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtContextReceiverList
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.lang.completion.CaretAction
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtCollectionLiteralExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtLabelReferenceExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtModifierKeywordToken
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtOperationReferenceExpression
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSimpleNameStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeList
import dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.kotlin.syntax.psi.findElementAtIncludingTrivia
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.MemberPopularity
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.KotlinPerf
import dev.ide.lang.kotlin.interp.PreviewConstants
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.parse.climbTo
import dev.ide.lang.kotlin.parse.identifierPrefixBefore
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef

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
    // of the buffer; keeping the last spliced tree lets the next one parse only the body the typed character
    // and the moved marker are in, instead of the whole file each keystroke. Kept SEPARATE from the
    // analyzer's lastByFile tree because this one carries the marker. Bounded to one file (the focused one);
    // a different path full-parses and replaces it. Touched only on the single serialized engine worker
    // (completion lane).
    private var splicedPath: String? = null
    private var splicedTree: KtFile? = null

    /** Parse [spliced] (the marker-spliced buffer for [path]) and update the single-slot cache. */
    private fun parseSpliced(name: String, path: String, spliced: String): KtFile {
        val previous = splicedTree?.takeIf { splicedPath == path }
        if (previous != null && previous.text.contentEquals(spliced)) return previous
        val kt = if (previous != null) KotlinParserHost.reparse(previous, name, spliced) else KotlinParserHost.parse(name, spliced)
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
        document: DocumentSnapshot, requestedOffset: Int,
        /** The second pass IntelliJ runs by itself when the first finds nothing (see [KotlinSymbolService.staticCallablesByName]). */
        widened: Boolean = false,
    ): CompletionResult {
        KotlinPerf.span("onBefore") { onBeforeComplete() }
        val original = document.text.toString()
        val offset = requestedOffset.coerceIn(0, original.length)
        val prefix = identifierPrefixBefore(original, offset)
        // The graded matcher every candidate gate below shares: exact/prefix/camel-hump/substring, so
        // `mDL` reaches `myDynamicList` through the same filter chain a plain prefix takes.
        val matcher = PrefixMatcher(prefix)
        // IntelliJ's completion matcher, for the sources that match the way it does (see [KotlinNameMatcher]).
        val wordMatcher = KotlinNameMatcher(prefix)
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

        // Splice the marker right at the caret so the parser yields a real reference node even after `.`. An
        // unclosed `super<` gets the `>` IntelliJ's dummy identifier adds there, so the qualifier parses as one.
        val closeSuperQualifier = followingChar != '>' && KotlinSuperAndWhereCompletion.inUnclosedSuperQualifier(original, offset - prefix.length)
        val spliced = original.substring(0, offset) + MARKER + (if (closeSuperQualifier) ">" else "") + original.substring(offset)
        val kt = KotlinPerf.span("parse") {
            parseSpliced(
                document.file.name, document.file.path, spliced
            )
        }
        val parsed = KotlinParsedFile(kt, document.file, document.version)
        // Same-file freshness: a class/member declared in THIS buffer (`with(LocalClass()) { … }`) resolves from
        // the live PSI. Keyed by the marker-free text hash so it shares the focal entry analyze/highlight set
        // (a no-op when already synced); the marker sits at the caret, leaving referenced declarations intact.
        KotlinPerf.span("focal") {
            runCatching {
                service.syncFocal(document.file.path, original.hashCode()) {
                    SourceIndexBuilder.extractFrom(
                        kt, parsed, document.file.path
                    )
                }
            }
        }
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        val visibility = KotlinCompletionVisibility(markerLeaf, service, document.file.path, resolver.fileContext.packageName)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        // A character literal (`'a|'`) holds a character, not code: a `'` before the token only opens one.
        if (insideStringLiteral(markerLeaf) || original.getOrNull(offset - prefix.length - 1) == '\'') {
            if (insideStringLiteral(markerLeaf)) templateDotCompletion(document, original, offset, prefix)?.let { return it }
            return CompletionResult(
                emptyList(), isIncomplete = false, replacementRange = replaceRange
            )
        }
        // Inside a KDoc `/** */`: offer doc tags (`@param`, `@return`, …) / the documented declaration's
        // parameter names after `@param`, and otherwise NOTHING — never the scope symbols/keywords a plain
        // name-ref classification would wrongly leak into a doc comment.
        kdocCompletion(kt, markerLeaf, original, offset, prefix, matcher)?.let { kdocItems ->
            return CompletionResult(kdocItems, isIncomplete = false, replacementRange = replaceRange)
        }
        // Inside a `//` or `/* */` comment: prose, nothing to complete. Without this the caret's word was classified
        // as a name reference and the popup offered classes (`// changed` -> ChangedPackages, OnSizeChangedNode),
        // and the Enter that ends the comment line accepted the first one (seen on the emulator).
        if (insideComment(kt, offset)) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
        // A label reference (`return@<caret>`, `break@`/`continue@<caret>`, `this@`/`super@<caret>`): offer the
        // enclosing labels, NOT scope locals + type names — which is what a plain name-ref classification would
        // wrongly surface here (a jump target is never a local or a type).
        labelCompletion(markerLeaf, matcher, resolver)?.let { labelItems ->
            return CompletionResult(labelItems, isIncomplete = false, replacementRange = replaceRange)
        }
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf)
        // `where <caret>` names a type parameter of the declaration, `super<<caret>` a direct supertype of the class:
        // each is its own small list, never the scope's symbols.
        (KotlinSuperAndWhereCompletion.whereClauseItems(markerLeaf, nameRef, matcher)
            ?: KotlinSuperAndWhereCompletion.superQualifierItems(markerLeaf, matcher, resolver, service, closeSuperQualifier))?.let { fixed ->
            return CompletionResult(fixed, isIncomplete = false, replacementRange = replaceRange)
        }
        // A `..` that is not a range (`class Test..`, `package a..`, `val b..`), or a range whose left side has
        // no `rangeTo` operator: IntelliJ leaves the position to command completion and offers nothing.
        if (strayRangeDots(original, offset - prefix.length, nameRef, resolver)) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
        // A `.` with nothing before it to be the receiver (`{ .<caret> }`): there is no member to complete.
        if (nameRef != null && original.getOrNull(offset - prefix.length - 1) == '.' &&
            original.getOrNull(offset - prefix.length - 2) != '.' && danglingDot(nameRef)
        ) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
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
                CompletionPosition.TypeReference -> qualifiedTypeCandidates(markerLeaf, prefix, resolver)?.let { q ->
                    // `a.b.<caret>` / `Outer.<caret>` in a type: what the qualifier holds, never every type by name.
                    val annotation = inAnnotationNamePosition(markerLeaf)
                    PositionResult(
                        q.filter { s -> s.kind == SymbolKind.PACKAGE || (if (annotation) s.kind == SymbolKind.ANNOTATION_TYPE else s.kind in TYPE_KINDS) },
                        packageCompletion = true,
                    )
                } ?: service.typeNameCandidates(prefix, currentFilePath = resolver.fileContext.path).let {
                    val aliases = if (inAnnotationNamePosition(markerLeaf)) emptyList() else importAliases(resolver, matcher, typesOnly = true)
                    // At an annotation NAME (`@Comp…`) only annotation classes belong — restrict to them so the
                    // popup isn't every classifier (IntelliJ's `@` filter). Non-annotation position → all types.
                    // The root packages come along, as a type may be written fully qualified (`kotlin.`).
                    val syms = if (inAnnotationNamePosition(markerLeaf))
                        // A bare name also sees the default-imported ones; a qualified one (`@kotlin.<caret>`) the
                        // annotation classes of the package it names.
                        (it.symbols + annotationsAt(original, offset - prefix.length, prefix) + service.typeAliasCandidates(prefix))
                            .filter { s -> s.kind == SymbolKind.ANNOTATION_TYPE } + service.knownRootPackages(prefix)
                    else typeParametersAround(markerLeaf, matcher) + it.symbols + defaultScopeMiddleMatches(prefix) + service.typeAliasCandidates(prefix) + aliases + service.knownRootPackages(prefix)
                    // `when (subject) { is <caret> }` on a SEALED subject → surface its subclasses. They're
                    // assignable to the subject type, so passing it as `expected` floats them above every other
                    // classifier (the rank's fits-expected key); adding them explicitly guarantees they appear
                    // even past the type-name cap. A non-sealed / subjectless `when` keeps the plain type list.
                    val sealedSubject = sealedWhenSubject(markerLeaf, resolver)
                    if (sealedSubject != null) {
                        val subs = service.sealedSubtypeCandidates(sealedSubject.qualifiedName, prefix).orEmpty()
                        PositionResult(subs + syms, expected = sealedSubject, keywordContext = true, capped = it.capped, preferSubtypes = true)
                    } else {
                        // A `catch (e: <caret>)` parameter is a Throwable: its subclasses rank first.
                        val caught = if (inCatchParameterType(markerLeaf)) service.typeByFqn("kotlin.Throwable") else null
                        // A context parameter's name being written reads as a type here (`context(f<caret>)`).
                        PositionResult(
                            syms, extra = parameterNameAndTypeItems(markerLeaf, prefix, resolver, autoImport),
                            expected = caught, keywordContext = true, capped = it.capped,
                            aliased = aliases.toHashSet(),
                        )
                    }
                }

                is CompletionPosition.InfixName -> infixCandidates(
                    where.left, offset, resolver, matcher
                )

                CompletionPosition.NameReference -> nameReferenceCandidates(
                    markerLeaf, offset, matcher, resolver, autoImport, widened
                )
            }
        }
        // An annotation's argument is a compile-time constant, so only what can be one is offered there.
        val constantsOnly = insideAnnotationArguments(markerLeaf)
        // `super.` (IntelliJ's `K2SuperMemberCompletionContributor`): a member several supertypes provide, once per
        // supertype, each inserted through `super<T>.`; and a call passing the parameters of the override being
        // written through to the member it overrides.
        val superItems = if (pos.memberAccess) KotlinPerf.span("super") { superMemberItems(nameRef, matcher, resolver, followingChar) } else null
        val raw0 = if (constantsOnly) compileTimeConstants(pos.raw, pos.expected, pos.receiverChain.firstOrNull(), resolver) else pos.raw
        val raw1 = if (superItems == null) raw0 else superItems.members.filter { it.name !in superItems.replaced }
        // What a `::` reference cannot name (`K2CallableReferenceCompletionContributor.filter`).
        val raw = if (callableRef) raw1.filter(::referenceableByCallableReference) else raw1

        // Inside a @Composable context (a `setContent`/`Column` content lambda, a @Composable function body),
        // float @Composable callables to the top — Android Studio's Compose weigher. NOT a filter: non-composable
        // code (`remember`, `println`, locals, control flow) stays available, just ranked below.
        val composableContext = KotlinPerf.span("composeCtx") {
            resolver.composableContextAt(offset) == ComposableContext.COMPOSABLE
        }

        // The property whose initializer the caret is in (`val xxx: String = <caret>`): it cannot read itself
        // there, so it is not offered.
        val initializing = propertyBeingInitialized(markerLeaf)
        // A `when (subject)` branch condition, ranked by its own groups (see [KotlinCompletionWeighing.rankWhenConditionGroups]).
        val whenExpr = if (pos.nameReference) KotlinCompletionWeighing.whenSubjectConditionAt(markerLeaf) else null
        // IntelliJ's `ExpectedTypeWeigher` puts what has no type to judge (a classifier that is not the expected one,
        // a keyword) above what mismatches it, before any later weigher. The engine's expected-type key is a flag, so
        // the untyped ones carry this on the popularity key, the first one past the match grade.
        val untypedBonus = if (pos.expected != null && whenExpr == null) UNTYPED_BONUS else 0
        // How many candidates matched the prefix before the page was cut to MAX_ITEMS (see isIncomplete).
        var matchedCount = 0
        val candidates = KotlinPerf.span("rank") {
            val packageCompletion = pos.packageCompletion
            val expected = pos.expected
            val nameReference = pos.nameReference
            val chain = pos.receiverChain
            // An accessor overload a property doesn't stand for (`setText(Int)` next to `text`) leaves the
            // popularity to the property, or the overloads crowd the top of the list ahead of it.
            val properties = pos.properties
            val out = ArrayList<Candidate>(raw.size)
            val seen = HashSet<String>()
            val lambdaTwins = if (pos.memberAccess) lambdaOnlyOverloads(raw) else null
            // A `get`/`set` prefix on a member access also reaches the properties its accessor names.
            val accessorTail = pos.memberAccess && (accessorPrefixTail(prefix) != null || getterHead(prefix))
            // The names a candidate is compared with for IntelliJ's argument-name similarity: the parameter an
            // argument fills, or the declaration whose type is being written. It rides the popularity slot, which
            // only a receiver's members otherwise fill.
            val referenceNames = if (chain.isEmpty()) KotlinCompletionWeighing.referenceNames(markerLeaf, resolver) else emptyList()
            // The local functions in scope, by name: their symbols look like top-level ones (no owner, no
            // package), but they rank with the locals. The walk allocates nothing when there are none.
            val localFunctions = if (nameReference) resolver.localFunctionsInScope(offset).mapTo(HashSet()) { it.name } else emptySet()
            // The smart casts at the caret, for the values the expected type would judge by them: a bare name, or
            // a member read through an explicit receiver whose path a check can name (`box.value`).
            val smartCasts = if (expected == null) null else {
                val q = nameRef?.parent as? KtQualifiedExpression
                val receiverPath = if (q != null && q.selectorExpression === nameRef) completionAccessPath(q.receiverExpression) else null
                if (q != null && q.selectorExpression === nameRef && receiverPath == null) null
                else SmartCasts(resolver, offset, receiverPath)
            }
            // The overrides being written around the caret, for IntelliJ's `PreferContextualCallablesWeigher`; after
            // `super.` they outrank even the expected type, as there.
            val contextualOverrides = contextualOverrides(markerLeaf)
            // The classes nested in a sealed expected type are in reach by their simple names (see [Candidate]).
            val sealedExpectedPrefix = expected?.qualifiedName?.takeIf { service.sourceClass(it)?.isSealed == true }?.let { "$it." }
            val receiverLevels = if (nameReference) ImplicitReceiverLevels(resolver, offset) else null
            // The local classes in scope, which `ClassifierWeigher` puts before every other classifier.
            val localTypes by lazy { runCatching { resolver.localTypesInScope(offset).values.toHashSet() }.getOrDefault(emptySet()) }
            val superReceiver = (nameRef?.parent as? KtQualifiedExpression)?.let { q ->
                q.selectorExpression === nameRef && q.receiverExpression is KtSuperExpression
            } == true
            fun isLocal(s: KotlinSymbol) = s.kind == SymbolKind.METHOD && s.owner == null && s.packageName == null &&
                s.origin.fromSource && s.name in localFunctions
            for ((index, s) in raw.withIndex()) {
                val name = s.name
                if (name == "_" || MARKER in name) continue
                // `sumOf` has seven overloads that differ only in what the selector returns; each is typed
                // the same way, so one row stands for all of them (see [lambdaOnlyOverloads]).
                if (lambdaTwins != null && lambdaTwins[index]) continue
                // A middle match the local scope let through (see [KotlinNameMatcher]) ranks after every other.
                val grade = matcher.grade(name)
                    ?: if (wordMatcher.matches(name) || (accessorTail && accessorMatches(s, prefix))) PrefixMatcher.Grade.SUBSTRING else continue
                if (!firstLetterCaseAgrees(prefix, name)) continue
                if (!visibility.visible(s)) continue
                if (initializing != null && name == initializing.name && isDeclaredBy(s, initializing)) continue
                // Dedup overloads by name+kind+signature, but TYPES by fully-qualified name: two DISTINCT
                // classes sharing a simple name (`androidx.compose.ui.Modifier` vs `java.lang.reflect.Modifier`)
                // must BOTH survive. The bytecode index records every type as kind "class", so a name+kind key
                // collapsed them to whichever the index returned first — dropping the Compose `Modifier`
                // on-device, where the java.* one happened to sort ahead.
                // A type imported under an alias is the same class by another name: both are offered.
                val dedupKey =
                    if (s.kind in TYPE_KINDS) "T#" + ((s.type as? KotlinType)?.qualifiedName?.let { q -> if (q.substringAfterLast('.') == name) q else "$q#$name" } ?: name)
                    else name + "#" + s.kind + "#" + (s.signature ?: "")
                if (!seen.add(dedupKey)) continue
                // A type alias is judged as what it expands to (`ExpectedTypeWeigher` reads the expanded symbol).
                val judged = if (s.kind in TYPE_KINDS && expected != null) aliasExpanded(s) ?: s else s
                val verdict = if (expected == null) UNTYPED else expectedVerdict(judged, expected, nameReference, resolver, offset, smartCasts)
                val deprecated = deprecatedForRanking(s)
                val contextual = contextualOverrides.isNotEmpty() && s.kind == SymbolKind.METHOD && !s.isExtension &&
                    contextualOverrides[name]?.contains(s.paramNames.size) == true
                val fits = verdict == FITS
                val local = s.kind == SymbolKind.LOCAL_VARIABLE || s.kind == SymbolKind.PARAMETER || isLocal(s)
                // A local class is in scope where it is offered at all, and no import can name it.
                val via = if (pos.importVia.isEmpty()) null else pos.importVia[s]
                val importEdit = if (via != null) autoImport.editForType(via)
                else if (packageCompletion || (pos.memberAccess && s.kind in TYPE_KINDS) || s in pos.aliased ||
                    (s.kind in TYPE_KINDS && s.origin.fromSource && (s.type as? KotlinType)?.qualifiedName in localTypes)
                ) emptyList()
                else importEditFor(s, autoImport)
                out += Candidate(
                    symbol = s,
                    importEdit = importEdit,
                    fitsExpected = fits || (contextual && superReceiver),
                    grade = grade.ordinal,
                    boosted = composableContext && s.isComposable,
                    group = if (chain.isEmpty()) memberGroup(s) else receiverCallableGroup(s, chain),
                    proximity = expectedBand(verdict) * BAND + proximityOf(s, nameReference, objectAsValue = fits && isObjectValue(judged), local = local),
                    scopeOrder = when {
                        local -> localScopeOrder(s, index)
                        // A name the file imports explicitly under an alias is nearer than the class's own name.
                        s in pos.aliased -> -1
                        nameReference && isTopLevelSymbol(s) -> importScopeOf(s, autoImport)
                        // A top-level classifier by the scope that brings it in, as IntelliJ's `ClassifierWeigher`
                        // orders them by their scope's index in the tower: an explicit import before a star one.
                        s.kind in TYPE_KINDS && !packageCompletion && !pos.memberAccess ->
                            if ((s.type as? KotlinType)?.qualifiedName in localTypes) -2 else topLevelClassifierScope(s, autoImport)
                        else -> 0
                    },
                    functionKey = if (nameReference && s.kind == SymbolKind.METHOD) 1 else 0,
                    importTier = if (importEdit.isEmpty()) 0 else (via ?: importFqnOf(s))?.let(autoImport::notImportedTier) ?: 1,
                    deprecated = deprecated,
                    contextual = if (contextual) 0 else 1,
                    receiverLevel = receiverLevels?.levelOf(s) ?: 0,
                    reachable = sealedExpectedPrefix != null && s.kind in TYPE_KINDS &&
                        (s.type as? KotlinType)?.qualifiedName?.startsWith(sealedExpectedPrefix) == true,
                    // IntelliJ's `kotlin.deprecated` weigher comes right after the expected type: a deprecated
                    // candidate sinks below every other one that fits as well, which the popularity key carries
                    // here (the engine's own deprecation key is compared last).
                    popularity = (if (deprecated) -DEPRECATED_PENALTY else 0) + (if (contextual && superReceiver) CONTEXTUAL_SUPER_BONUS else 0) +
                        // MATCHES_WITHOUT_NULLABILITY over NON_TYPABLE over NOT_MATCHES (see `untypedBonus`).
                        // A JDK-internal classifier (`sun.*`, which IntelliJ's default exclusions hide) is left below.
                        (if (verdict == FITS_NON_NULL) 2 * untypedBonus
                        else if (verdict == UNTYPED && packageRankOf(s) != PKG_INTERNAL) untypedBonus else 0) + when {
                        // A strict subtype of the preferred sealed type over the type itself, both over the rest
                        // (the expected-type key already put both first).
                        pos.preferSubtypes -> if (fits && (s.type as? KotlinType)?.qualifiedName != expected?.qualifiedName) 1 else 0
                        chain.isEmpty() -> if (referenceNames.isEmpty()) 0 else KotlinCompletionWeighing.nameSimilarity(name, referenceNames)
                        s.kind == SymbolKind.METHOD && accessedProperty(name) in properties -> MemberPopularity.of(chain, name) / 2
                        else -> MemberPopularity.of(chain, name)
                    },
                    distance = if (chain.isEmpty() || s.isExtension) 0 else distanceIn(chain, s),
                    lengthKey = 0,
                )
            }
            out.sortWith(RANK)
            matchedCount = out.size
            if (out.size > MAX_ITEMS) out.subList(MAX_ITEMS, out.size).clear()
            out
        }

        // Smart TYPE-candidate insertion (angle brackets for a generic, a `()` constructor call for a
        // superclass / instantiable value). The facts need the symbol service, so compute them here (see
        // [typeInsertFacts]) and hand the pure item builder two plain ints.
        //  - A DIRECT entry in a class's supertype list (`class Foo : Ba█`): the type reference's parent is a
        //    supertype-list entry — unlike a nested type argument (`class Foo : List<Ba█>`), whose reference
        //    sits under a type projection. A class there is a constructor call; an interface stays bare.
        val superTypeEntry = climbTo<KtTypeReference>(markerLeaf)?.parent is KtSuperTypeListEntry
        // A type reference by position, or a type offered into a declaration's RECEIVER slot ([PositionResult.
        // typeInsert]). Either way a generic candidate inserts its `<>`.
        val typePosition = inTypePosition(markerLeaf) || pos.typeInsert
        // A class name typed as a statement of its own (`System█`, `Toast█` at the start of a line) is nearly
        // always the head of a member access (`System.out`, `Toast.makeText`), not a constructor call whose
        // result is thrown away, so only a real value position (an initializer, an argument, a `return`) gets
        // the `()`.
        val valuePosition = pos.nameReference && nameRef != null && !isStatement(nameRef)

        // Types get their docs from the attached sources / platform docs pack here; members already have theirs
        // (see KotlinSymbolService.enrich). Every candidate the page can hold is covered, not a guessed top few:
        // the engine re-ranks after this, so a guess left the first row (`ScrollView`) without its doc. A miss is
        // a directory lookup, and a hit decodes its package once into the provider's cache.
        val typeDocs = HashMap<KotlinSymbol, String>()
        candidates.asSequence()
            .filter { it.symbol.documentation().isNullOrBlank() && !it.symbol.origin.fromSource }
            .take(MAX_ITEMS)
            .forEach { c -> service.typeDocForCompletion(c.symbol)?.let { typeDocs[c.symbol] = it } }
        // The class a value position expects (`return Foo█` in a function returning `Foo`) completes twice, as
        // IntelliJ's type-instantiation contributor has it: the class itself, inserted bare, and right after it
        // a constructor call (`Foo(a: Int)`). Any other class in a value position keeps inserting the call.
        val expectedClass = pos.expected?.qualifiedName?.takeIf { valuePosition && followingChar != '.' && !pos.memberAccess }
        val symbolItems = candidates.flatMap { c ->
            val instantiated = expectedClass != null && c.symbol.kind == SymbolKind.CLASS &&
                (c.symbol.type as? KotlinType)?.qualifiedName == expectedClass
            val (typeParamCount, ctorRequiredArgs) =
                typeInsertFacts(c.symbol, superTypeEntry, valuePosition && !instantiated, typePosition, followingChar)
            val ctorItem = if (instantiated) constructorCallItem(c, followingChar) else null
            if (ctorItem != null) return@flatMap listOf(
                KotlinCompletionItems.toItem(c.symbol, c.importEdit, followingChar, c.relevance(), typeParamCount = typeParamCount),
                ctorItem,
            )
            KotlinCompletionItems.toItem(
                c.symbol,
                c.importEdit,
                followingChar,
                c.relevance(),
                infix = pos.infixInsert,
                callableRef = callableRef,
                bareCallable = pos.bareCallable,
                typeParamCount = typeParamCount,
                ctorRequiredArgs = ctorRequiredArgs,
            ).let { item ->
                typeDocs[c.symbol]?.let { item.copy(documentation = it) } ?: item
            }.let { item ->
                // A platform API's doc opens with the level that added it ("Added in API 26", "Since Java 11").
                service.apiLevelOf(c.symbol)?.let { item.copy(documentation = service.withApiLevel(c.symbol, item.documentation)) } ?: item
            }.let { item ->
                // The trailing-lambda form (`forEach {...}`), when there is one, ranks just above the plain call.
                val lambda = KotlinCompletionItems.trailingLambdaVariant(
                    c.symbol, item, followingChar, infix = pos.infixInsert, callableRef = callableRef, bareCallable = pos.bareCallable,
                )
                if (lambda != null) listOf(lambda, item) else listOf(item)
            }
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
                hasType = service::isKnownType,
            )
            refineReceiverKeywords(tail, markerLeaf, offset, prefix, resolver)
            tail.retainAll { it.kind != CompletionItemKind.KEYWORD || firstLetterCaseAgrees(prefix, it.label) }
            if (constantsOnly) tail.retainAll { it.kind != CompletionItemKind.KEYWORD || it.label in CONSTANT_KEYWORDS }
            // A plain `return` leaves the enclosing function, which only an inlined lambda may do; inside any
            // other lambda only its labeled `return@…` is legal.
            if (tail.any { it.kind == CompletionItemKind.KEYWORD && it.label == "return" } && !plainReturnAllowed(markerLeaf, resolver)) {
                tail.removeAll { it.kind == CompletionItemKind.KEYWORD && it.label == "return" }
            }
            // `return null` / `return true` / `return emptyList()` beside `return`, from the declared return type.
            if (tail.any { it.kind == CompletionItemKind.KEYWORD && it.label == "return" }) {
                val at = tail.indexOfFirst { it.kind == CompletionItemKind.KEYWORD && it.label == "return" }
                tail.addAll(at + 1, returnValueShortcuts(markerLeaf, prefix))
            }
            // In an expression or declaration position the keywords rank among the symbols, after the
            // callables and before the classifiers, as IntelliJ ranks them (see [KotlinCompletionWeighing]).
            if (pos.nameReference) KotlinCompletionWeighing.placeKeywords(tail, nameRef, pos.expected, untypedBonus) { label ->
                // `this` as a check narrowed it (`if (this@Foo is Bar)`), else as declared.
                runCatching { resolver.smartCastTypeAt(label, offset) }.getOrNull() ?: thisTypeAt(label, markerLeaf, offset, resolver)
            }
        }

        // `Foo::class` and `Foo::class.java` after a qualified `::` (`K2ClassReferenceCompletionContributor`).
        if (callableRef && pos.memberAccess && "class".startsWith(prefix)) {
            tail += CompletionItem("class", "class", CompletionItemKind.KEYWORD)
            tail += CompletionItem("class.java", "class.java", CompletionItemKind.KEYWORD)
        }

        // A `when (subject)` branch condition: the classes also as `is X` checks, and an `else -> ` branch.
        val conditionItems = if (whenExpr != null) {
            val subjectFqn = pos.expected?.qualifiedName
            val checks = when {
                subjectFqn == null -> KotlinCompletionWeighing.IsChecks.ALL
                service.enumConstantsOf(subjectFqn).isNotEmpty() -> KotlinCompletionWeighing.IsChecks.NONE
                // A sealed subject is checked against its inheritors, unless one of them is abstract: then any class
                // may be (`K2WhenWithSubjectConditionContributor.completeSubClassesOfSealedClass`).
                service.sealedSubclassesOf(subjectFqn) != null && !hasAbstractSealedInheritor(subjectFqn, HashSet()) ->
                    KotlinCompletionWeighing.IsChecks.FITTING
                else -> KotlinCompletionWeighing.IsChecks.ALL
            }
            KotlinCompletionWeighing.whenConditionItems(symbolItems, subjectFqn, ::isObjectValue, ::nestedClassName, checks) { s ->
                topLevelClassifierScope(s, autoImport)
            }
        } else symbolItems
        if (whenExpr != null) KotlinCompletionWeighing.elseBranchItem(whenExpr, prefix)?.let { tail += it }

        // Reserve room so the (small) keyword/template/postfix tail is never starved by a large symbol set —
        // otherwise an empty-prefix popup (hundreds of in-scope symbols) would truncate the keywords away.
        val keep = (MAX_ITEMS - tail.size).coerceAtLeast(0)
        // `[]` / `()` for the indexing and invoke operators of a `receiver.` (IntelliJ's bracket-operator items).
        val bracketItems = if (prefix.isEmpty() && pos.memberAccess) bracketOperatorItems(nameRef, raw, original, offset, followingChar) else emptyList()
        val head = (bracketItems + superItems?.items.orEmpty() + pos.extra.distinctBy { it.kind to it.label })
            .filter { item -> (item.symbol as? KotlinSymbol)?.let(visibility::visible) != false } + conditionItems
        val items =
            (head.take(keep) + tail)
                // Include `container` so two distinct types with the same simple name (different packages) both
                // remain — otherwise this pass re-collapses the pair the symbol dedup above kept.
                // A keyword's variants share its label and differ in what they insert (`data class` / `data class
                // Foo()`), so those count too.
                .distinctBy { listOf(it.kind, it.label, it.container, if (it.kind == CompletionItemKind.KEYWORD) it.insertText else null) }
                .take(MAX_ITEMS)
                .let {
                    if (whenExpr == null) it
                    else KotlinCompletionWeighing.rankWhenConditionGroups(it, ::isObjectValue, (whenExpr.subjectExpression as? KtNameReferenceExpression)?.getReferencedName())
                }
        // IntelliJ reruns a completion that found nothing with a higher invocation count, which reaches the members
        // of objects and the statics of Java classes by name too (`IncreaseInvocationCountIfNothingFound`). So
        // does this, once, for a bare name: an empty page cost little, and the second pass only runs then.
        if (!widened && items.isEmpty() && prefix.isNotEmpty() && pos.nameReference) {
            return completeInner(document, requestedOffset, widened = true)
        }
        return CompletionResult(
            items = items,
            // Incomplete when the final set was truncated OR a producer capped its query — either way matches
            // exist that this page doesn't hold, so the engine must re-query as the prefix narrows instead of
            // narrowing this page client-side (which would permanently hide, e.g., `StringBuilder` typed from `S`).
            // Also incomplete while the classpath index is still BUILDING: this page was served from whatever
            // segments are open so far, so a classpath type (`Modifier`, …) that indexes moments later is absent
            // — force a re-query on each keystroke until the index is ready, otherwise the editor caches this
            // pre-index page as complete and a fast typist never sees the type appear (cf. LearnBackend).
            //
            // "Truncated" counts the candidates that MATCHED the prefix, not the raw set before filtering: a
            // candidate the matcher rejects for this prefix cannot match any longer one (every tier it grades,
            // prefix, camel hump and substring alike, only narrows as characters are added), so it is not a
            // missing match. Counting the raw set marked nearly every page incomplete, which made the popup
            // re-query the engine on every keystroke instead of narrowing locally.
            // The one gap is the substring tier, which only switches on at MIN_SUBSTRING_QUERY typed chars, so
            // a page narrowed locally from a shorter prefix never gains a middle match. Accepted, and measured
            // (EditorContentionBenchmark): a middle match is the lowest tier, and re-querying the first
            // characters of every word to recover it cost more engine time than it was worth.
            isIncomplete = matchedCount > MAX_ITEMS || head.size > keep || pos.capped || !service.classpathReady(),
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
        /** For [memberAccess] on a typed receiver: its FQN, then its supertypes nearest first. Ranks members by
         *  how far up they are declared and scores them against [MemberPopularity]. */
        val receiverChain: List<String> = emptyList(),
        /** For [memberAccess]: the Kotlin properties standing for a Java getter (see [gettableProperties]). */
        val properties: Set<String> = emptySet(),
        /** The completion is a segment of an `import`/`package` directive, so a callable candidate is inserted as
         *  its BARE name (`import a.b.map`), never the call form `map()` — unlike a fully-qualified call in code
         *  (`a.b.map(...)`), where the parens are wanted. */
        val bareCallable: Boolean = false,
        /** The candidates are TYPE names being inserted as a type reference even though the caret isn't inside
         *  one: the receiver slot of a declaration name (`fun <caret>` -> `fun List<>.…`). Makes a generic
         *  candidate insert its `<>` exactly as a real type position does. */
        val typeInsert: Boolean = false,
        /** Candidates already imported under another name (`import a.B as C`), which need no import edit. */
        val aliased: Set<KotlinSymbol> = emptySet(),
        /** The [expected] type is a sealed type whose strict subtypes rank ahead of it (IntelliJ's
         *  `PreferredSubtypeWeigher`: PREFERRED_SUBTYPE, then PREFERRED_EXACT_TYPE, then the rest). */
        val preferSubtypes: Boolean = false,
        /** Candidates imported through their container rather than by their own FQN (an object's member, a
         *  Java static, see [KotlinSymbolService.staticCallablesByName]), with the FQN the import spells. */
        val importVia: Map<KotlinSymbol, String> = emptyMap(),
    )

    private fun classifyPosition(
        markerLeaf: KtElement?, nameRef: KtNameReferenceExpression?
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

    /**
     * The candidates after the qualifier of a qualified type (`kotlin.collections.<caret>`, `Map.<caret>`,
     * `@kotlin.concurrent.<caret>`): the nested types of the type it names, else the sub-packages and types of
     * the package it names. Null when the type at [leaf] is not qualified.
     */
    private fun qualifiedTypeCandidates(leaf: KtElement?, prefix: String, resolver: KotlinResolver): List<KotlinSymbol>? {
        val type = climbTo<KtUserType>(leaf) ?: return null
        val qualifier = type.qualifier ?: return null
        val text = qualifier.text.replace(TYPE_ARGUMENTS, "").replace(" ", "")
        if (text.isEmpty()) return emptyList()
        val fqn = runCatching { service.resolveTypeName(text, resolver.fileContext) }.getOrNull()
            ?.takeIf { runCatching { service.isKnownType(it) }.getOrDefault(false) }
        if (fqn != null) {
            val nested = service.nestedTypesOf(fqn, prefix) +
                service.membersForCompletion(fqn, emptyList(), prefix).filter { it.kind in TYPE_KINDS }
            return nested.distinctBy { (it.type as? KotlinType)?.qualifiedName ?: it.name }
        }
        return service.packageMembers(text, prefix)
    }

    /** When [leaf] is the operation-reference slot of a binary expression (`a foo█`, `0 downTo█`), the left
     *  operand whose type the infix function must resolve against; null otherwise (a right operand `a + b█` is
     *  a plain name reference, not the operation reference). */
    private fun infixOperatorLeft(leaf: KtElement?): KtExpression? {
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
        callableRef: Boolean = false,
        /** A class the receiver names only once it is imported (see [unimportedClassReceivers]). */
        unimported: String? = null,
    ): PositionResult {
        val prefix = matcher.prefix
        if (unimported == null && !callableRef) unimportedClassReceivers(receiver, resolver)?.let { classes ->
            return unimportedReceiverCandidates(classes, receiver, offset, resolver, matcher)
        }
        // Classes the caret sits inside — a private member of one of them is accessible via `.`.
        val enclosing = enclosingClasses(receiver)
        // A bare type-parameter receiver (`t.` where `t: T`, `<T : Bound>`) completes against the parameter's
        // upper bound; a normal type is unchanged, an unbounded parameter drops to the package/empty path.
        // A project typealias named as the receiver (`typealias AA = A`, then `AA.`) stands for the class it
        // aliases, chains of aliases included: an object's members, or a class's statics and companion. Nested
        // classes are not reached through an alias (KT-34281).
        val aliasTarget = unimported ?: aliasReceiverTarget(receiver, resolver)
        // A jump (`break.`, `return.`, `throw x.`) produces no value to call anything on: nothing to complete.
        val bare = generateSequence(receiver) { (it as? dev.ide.kotlin.syntax.psi.KtParenthesizedExpression)?.expression }.last()
        if (bare is dev.ide.kotlin.syntax.psi.KtReturnExpression || bare is dev.ide.kotlin.syntax.psi.KtBreakExpression ||
            bare is dev.ide.kotlin.syntax.psi.KtContinueExpression || bare is dev.ide.kotlin.syntax.psi.KtThrowExpression
        ) return PositionResult(emptyList())
        val recvType = aliasTarget?.let { service.typeByFqn(it) } ?: KotlinPerf.span("infer") {
            (resolver.inferType(receiver) ?: contextParameterPathType(receiver, resolver) ?: lowercaseObjectReceiver(receiver, resolver))
                ?.let { resolver.receiverForMembers(it, receiver.textRange.startOffset) }
        }
        if (recvType != null) {
            val typeReceiver = if (aliasTarget != null) !service.isSingletonObject(aliasTarget) else resolver.isTypeReceiver(receiver)
            val chain = supertypeChain(recvType)
            val members = KotlinPerf.span("members") {
                val direct = service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
                // `getP` / `setP` also reaches a property through its accessor's name, which IntelliJ gives it as a
                // lookup string (`absolutePath` answers to `getAbsolutePath`): the properties of the rest of the name.
                val accessorTail = accessorPrefixTail(prefix)
                when {
                    // Every property, which `g` reaches through its getter's name: the whole member list, as an
                    // empty prefix lists it.
                    getterHead(prefix) -> direct + service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, "")
                        .filter { it.kind == SymbolKind.FIELD && !it.name.startsWith(prefix) }
                    accessorTail == null -> direct
                    else -> {
                        val viaTail = service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, accessorTail)
                            .filter { p ->
                                p.kind == SymbolKind.FIELD && accessorMatches(p, prefix) &&
                                    (p.origin.fromSource || !prefix.startsWith("set") || direct.any { isVoidSetterOf(it, p.name) })
                            }
                        // A setter's property is a property through its getter (see [gettableProperties]), which the
                        // `set` prefix did not list: listed now, it stays hidden behind the property.
                        val getters = if (!prefix.startsWith("set") || viaTail.isEmpty()) emptyList()
                        else service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, "get" + prefix.substring(3))
                            .filter { it.kind == SymbolKind.METHOD && it.paramTypes.isEmpty() }
                        direct + viaTail + getters
                    }
                }
            }.filter { callableRef || memberVisibleOn(it, typeReceiver, enclosing) }
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
                // A SOURCE type's nested types (`Outer.Nested`, a sealed interface's nested subclasses) are kept
                // out of its member list by SourceIndex, so — unlike a classpath binary's — they don't arrive
                // through `membersForCompletion`; add them explicitly so `Outer.` surfaces them.
                val nestedTypes = if (aliasTarget != null && unimported == null) emptyList() else service.nestedTypesOf(recvType.qualifiedName, prefix)
                // A JVM type NAMED BY ITS JAVA FQN (`java.lang.String.valueOf`, `java.lang.Integer.parseInt`): its
                // statics live on the JVM class, but Kotlin maps the type to a classifier whose API omits them, so
                // they never arrive through `members`. Offered ONLY when the receiver is SPELLED as the fully-
                // qualified name — a bare `String.` must stay static-free, since Kotlin exposes these statics only
                // through the `java.lang.String` spelling (both resolve to the same type here).
                val written = receiver.text.trim()
                val javaSpelling = written == recvType.qualifiedName ||
                    Builtins.kotlinTypeFor(recvType.qualifiedName)?.substringAfterLast('.') != written.substringAfterLast('.')
                val mappedStatics = if (javaSpelling)
                    service.mappedTypeStatics(recvType.qualifiedName)
                        .filter { matcher.matches(it.name) && memberVisibleOn(it, typeReceiver = true, enclosing) }
                else emptyList()
                // The scope member extensions that apply to that companion instance: `Modifier.weight` inside a
                // `Row { }`, where `Modifier.Companion : Modifier` and `RowScope` declares `fun Modifier.weight()`.
                val companionScopeExts = service.companionObjectFqn(recvType.qualifiedName)
                    ?.let { resolver.scopeMemberExtensions(offset, service.typeByFqn(it), prefix) }.orEmpty()
                members + nestedTypes + enumConstants + listOfNotNull(companion) + mappedStatics + service.companionMembersFor(
                    recvType.qualifiedName, prefix
                ).filter { memberVisibleOn(it, typeReceiver = false, enclosing) } + companionScopeExts
            } else {
                // Member-extensions in scope on an instance receiver (`map.printMap()` where `printMap` is a
                // `Map<…>` extension declared in the enclosing class, `Modifier.weight` inside a `Row { }`),
                // plus the ones an `import` would bring into scope: a project `object`/`companion object`'s
                // member extensions. Those are invisible until imported, which made the widespread "extensions
                // namespaced in an object" idiom undiscoverable: nothing was offered on the receiver, so there
                // was no way to reach `import util.StringUtils.twice` from the editor. Accepting one inserts
                // that import (see [importEditFor]).
                // An `is`-checked receiver also has the members of every other type it is known to have.
                val intersection = intersectionReceiverTypes(receiver, recvType, offset, resolver).flatMap { t ->
                    service.membersForCompletion(t.qualifiedName, t.typeArguments, prefix).filter { memberVisibleOn(it, false, enclosing) }
                }
                val inner = innerClassesOf(chain, matcher)
                // One list, filled in place: this is the hottest path, and each `+` copied the whole member list.
                val all = ArrayList<KotlinSymbol>(members.size + intersection.size + inner.size + 64)
                all += members
                all += intersection
                all += resolver.scopeMemberExtensions(offset, recvType, prefix)
                all += importableSingletonExtensions(recvType, prefix, resolver)
                functionTypeInvoke(recvType)?.takeIf { matcher.matches(it.name) }?.let { all += it }
                all.retainAll { extensionAppliesTo(it, recvType, resolver) }
                all += inner
                all
            }.let { importable(it, receiver, typeReceiver, recvType) }
            val properties = gettableProperties(raw)
            // The type the context wants of `receiver.name` ranks the members that have it first, as it does
            // a bare name (`val s: String = user.` floats `user.name`).
            val selector = climbTo<KtNameReferenceExpression>(resolver.elementAt(offset))
            val expected = KotlinPerf.span("expected") {
                if (KotlinCompletionWeighing.insideLambdaBeforeExpectation(selector)) null
                else resolver.expectedTypeAt(offset) ?: KotlinCompletionWeighing.operandExpectedType(selector, resolver)
            }
            return PositionResult(
                hideAccessorDuplicates(raw, prefix, properties),
                extra = if (typeReceiver) emptyList() else extensionFunctionValueItems(recvType, offset, matcher, resolver, declaredTypeOf(receiver, resolver)),
                expected = expected,
                memberAccess = true,
                receiverChain = chain,
                properties = properties.keys,
            )
        }
        // Receiver is a package/FQN prefix (`java.util.`, `android.`) — complete its sub-packages + the types in
        // it (inserted fully-qualified, so it needs no auto-import). Inside an `import`/`package` directive a
        // callable candidate inserts its bare name (`import a.b.map`), not the `map()` call form.
        val pkg = packagePathOf(receiver)
        val inImportOrPackage = climbTo<KtImportDirective>(receiver) != null || climbTo<KtPackageDirective>(receiver) != null
        return if (pkg != null) PositionResult(
            // In code a package reaches no extension (`pack.extFoo` calls nothing), only an import names one.
            service.packageMembers(pkg, prefix).let { ms -> if (inImportOrPackage) ms else ms.filterNot { it.isExtension } },
            packageCompletion = true, bareCallable = inImportOrPackage,
        )
        else PositionResult(emptyList())
    }

    /**
     * The other types an `is`-checked receiver is known to have at the caret (`a is A && a is B` makes `a.` an
     * `A & B`, plus whatever `a` was declared as): K2 completes the members of the intersection, while
     * [KotlinResolver.inferType] gives one type. Only the checks of the receiver's own access path inside the
     * declaration being written are read (no whole-file walk), and a receiver without one costs that walk only.
     */
    private fun intersectionReceiverTypes(receiver: KtExpression, recvType: KotlinType, offset: Int, resolver: KotlinResolver): List<KotlinType> {
        if (receiver !is KtNameReferenceExpression && receiver !is KtDotQualifiedExpression) return emptyList()
        val path = completionAccessPath(receiver) ?: return emptyList()
        var scope: KtElement? = receiver
        while (scope != null && scope !is KtNamedFunction && scope !is KtPropertyAccessor && scope !is KtClassOrObject) scope = scope.parent
        val body = scope ?: return emptyList()
        // No `is` written between the declaration's start and the caret: nothing to walk for.
        val text = resolver.ktFile.text
        val from = body.textRange.startOffset
        var hasIs = false
        var i = text.indexOf("is ", from)
        while (i in from until offset) {
            if (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') { hasIs = true; break }
            i = text.indexOf("is ", i + 3)
        }
        if (!hasIs) return emptyList()
        var refs: ArrayList<KtTypeReference>? = null
        fun visit(p: KtElement) {
            if (p.textRange.startOffset >= offset) return
            // A negated check counts too: `if (x !is T) return` proves `T` after it, which the flow decides.
            if (p is KtIsExpression) {
                val ref = p.typeReference
                if (ref != null && completionAccessPath(p.leftHandSide) == path) (refs ?: ArrayList<KtTypeReference>().also { refs = it }) += ref
            }
            var c = p.firstChild
            while (c != null) { visit(c); c = c.nextSibling }
        }
        visit(body)
        val checks = refs ?: return emptyList()
        val (proven, _) = runCatching { resolver.completionSmartCast(path, offset, mapOf(path to checks)) }
            .getOrDefault(emptyList<KotlinType>() to false)
        val declared = declaredTypeOf(receiver, resolver)
        return (proven + listOfNotNull(declared)).filter { !it.isTypeParameter && it.qualifiedName != recvType.qualifiedName }
            .distinctBy { it.qualifiedName }
    }

    /**
     * Inside an `import` directive, what [receiver] holds that an import can name: its nested classifiers, its
     * companion object, its enum entries and a Java class's statics, and an `object`'s own members. Never an
     * extension, an instance member, or a companion's member (`import A.foo` does not reach `A.Companion.foo`).
     * Outside an import, [raw] unchanged.
     */
    private fun importable(raw: List<KotlinSymbol>, receiver: KtExpression, typeReceiver: Boolean, recvType: KotlinType): List<KotlinSymbol> {
        if (climbTo<KtImportDirective>(receiver) == null) return raw
        val singleton = !typeReceiver && service.isSingletonObject(recvType.qualifiedName)
        val companionName = service.companionObjectSymbol(recvType.qualifiedName)?.name
        return raw.filter { s ->
            when {
                // An object's own member extension is imported through it (`import …Duration.Companion.seconds`);
                // an extension merely applicable to it (`also`) is not.
                s.isExtension -> singleton && (s.declaringClassFqn?.replace('$', '.') == recvType.qualifiedName ||
                    ((s.owner as? KotlinSymbol)?.type as? KotlinType)?.qualifiedName == recvType.qualifiedName)
                s.kind in TYPE_KINDS || s.kind == SymbolKind.ENUM_CONSTANT -> true
                s.name == companionName && s.kind != SymbolKind.METHOD && s.kind != SymbolKind.FIELD -> true
                singleton -> true
                // A Java class's static, which Kotlin imports by name.
                typeReceiver && Modifier.STATIC in s.modifiers && !s.origin.fromSource &&
                    s.declaringClassFqn?.endsWith("Companion") != true -> true
                else -> false
            }
        }
    }

    /** The `inner` classes declared by the project classes in [chain] (a receiver's class and its supertypes):
     *  an instance constructs one through itself (`b.InnerInA()`), so IntelliJ offers them after the dot. Read
     *  off each class's own declarations, so a library type in the chain costs one map lookup. */
    private fun innerClassesOf(chain: List<String>, matcher: PrefixMatcher): List<KotlinSymbol> {
        var out: ArrayList<KotlinSymbol>? = null
        for (fqn in chain) {
            val cls = (service.classDeclaration(fqn) as? KotlinDomNode)?.psi as? KtClassOrObject ?: continue
            for (d in cls.declarations) {
                val inner = d as? KtClass ?: continue
                if (!inner.isInner()) continue
                val name = inner.name ?: continue
                if (!matcher.matches(name)) continue
                (out ?: ArrayList<KotlinSymbol>().also { out = it }) +=
                    KotlinSymbol(name, SymbolKind.CLASS, service.typeByFqn("$fqn.$name"), origin = SymbolOrigin(fromSource = true, file = null))
            }
        }
        return out ?: emptyList()
    }

    /**
     * The classes a capitalized receiver that resolves to nothing could mean once imported (`Bar.` with `foo.Bar`
     * not imported): every class of that simple name the project and its libraries declare. IntelliJ completes the
     * static scope of each, and accepting an item imports its class. Null when the name resolves (a value, a
     * local, an imported or default-imported type) or no class is named so. Only a bare capitalized name pays
     * for the lookups, and only when it does not resolve as a type.
     */
    private fun unimportedClassReceivers(receiver: KtExpression, resolver: KotlinResolver): List<String>? {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (name.firstOrNull()?.isUpperCase() != true || receiver.parent !is KtDotQualifiedExpression) return null
        val at = receiver.textRange.startOffset
        if (resolver.localsAt(at, name).isNotEmpty()) return null
        val resolved = runCatching { service.resolveTypeName(name, resolver.fileContext, resolver.enclosingClassFqn(at)) }.getOrNull()
        if (resolved != null && service.isKnownType(resolved)) return null
        if (service.isProjectTypeAlias(name)) return null
        if (resolver.scopeSymbolsAt(at, namePrefix = name, exactName = true).any { it.name == name }) return null
        val classes = service.typeNamesByPrefix(name).mapNotNull { s ->
            (s.type as? KotlinType)?.qualifiedName?.takeIf { s.name == name && s.kind in TYPE_KINDS }
        }.distinct()
        return classes.ifEmpty { null }
    }

    /** What `Bar.` offers for each class [classes] names (see [unimportedClassReceivers]), every item importing its
     *  class. A class with a companion, or an object, is a value too, so `Any`'s members come along. */
    private fun unimportedReceiverCandidates(
        classes: List<String>, receiver: KtExpression, offset: Int, resolver: KotlinResolver, matcher: PrefixMatcher,
    ): PositionResult {
        val raw = ArrayList<KotlinSymbol>()
        val via = HashMap<KotlinSymbol, String>()
        for (fqn in classes) {
            val r = memberAccessCandidates(receiver, offset, resolver, matcher, unimported = fqn)
            for (s in r.raw) {
                raw += s
                via[s] = fqn
            }
            // An object is completed as the value it is, which leaves out its nested classes.
            if (service.isSingletonObject(fqn)) for (s in service.nestedTypesOf(fqn, matcher.prefix)) {
                raw += s
                via[s] = fqn
            }
            if (service.companionObjectFqn(fqn) != null) {
                for (s in service.membersForCompletion("kotlin.Any", emptyList(), matcher.prefix)) {
                    if (s.name in OBJECT_METHODS && !s.isExtension && raw.none { it.name == s.name && it.kind == s.kind }) {
                        raw += s
                        via[s] = fqn
                    }
                }
            }
        }
        return PositionResult(raw, memberAccess = true, importVia = via)
    }

    /** An `object` named in lower case (`object x`, then `x.`), which the resolver only looks up as a value: the
     *  singleton's type. Null for anything else, a local of that name included. */
    private fun lowercaseObjectReceiver(receiver: KtExpression, resolver: KotlinResolver): KotlinType? {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (name.firstOrNull()?.isLowerCase() != true) return null
        if (resolver.localsAt(receiver.textRange.startOffset, name).isNotEmpty()) return null
        val fqn = runCatching { service.resolveTypeName(name, resolver.fileContext, resolver.enclosingClassFqn(receiver.textRange.startOffset)) }
            .getOrNull() ?: return null
        return if (service.isKnownType(fqn) && service.isSingletonObject(fqn)) service.typeByFqn(fqn) else null
    }

    /** The class a receiver written as a project typealias's name stands for (see [memberAccessCandidates]), or
     *  null when the receiver is anything else (a local of that name shadows the alias). */
    private fun aliasReceiverTarget(receiver: KtExpression, resolver: KotlinResolver): String? {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (name.firstOrNull()?.isUpperCase() != true || !service.isProjectTypeAlias(name)) return null
        if (resolver.localsAt(receiver.textRange.startOffset, name).isNotEmpty()) return null
        return runCatching { service.typeAliasChainTargetFqn(name, resolver.fileContext) }.getOrNull()
    }

    /**
     * Whether the extension [s] (anything else passes) can be called on [recv] as completion offers it, by the
     * checks the candidate lookup leaves out: an extension declared as a MEMBER of a class (`class Foo { fun
     * Int.bar() }`) on a receiver of another type is not a member of that receiver (`foo.bar` does not exist);
     * a generic receiver's bound (`fun <T : B> T.ext()`) must admit the receiver; and the bounds on the type
     * arguments of the receiver's own class (`fun <S : Base> Some<S>.ext()` on a `Some<Other>`) must admit its
     * arguments. Only what can be judged for sure removes a candidate: an unknown type keeps it.
     */
    private fun extensionAppliesTo(s: KotlinSymbol, recv: KotlinType, resolver: KotlinResolver): Boolean {
        if (!s.isExtension) return true
        val receiverFqn = s.receiverTypeFqn ?: return true
        // A member extension of a class, whose receiver type is not the receiver's.
        val owner = (s.owner as? KotlinSymbol)?.takeIf { it.kind in TYPE_KINDS }
        if (owner != null && s.origin.fromSource && s.receiverTypeParam == null && receiverFqn != recv.qualifiedName &&
            runCatching { !service.typeByFqn(receiverFqn).isAssignableFrom(recv) }.getOrDefault(false)
        ) return false
        // The bounds are read from a project extension's own declaration: the symbol carries one erased bound per
        // type parameter (none from a `where` clause) and its receiver arguments arrive already substituted.
        // A symbol read back from the source index carries no declaration; one declared in this file is found
        // by name among its top-level functions.
        val fn = (s.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction
            ?: (if (s.origin.fromSource && s.typeParameters.isNotEmpty()) sameFileExtension(s, resolver) else null)
            ?: return true
        if (fn.typeParameters.isEmpty()) return true
        val bounds = HashMap<String, MutableList<String>>()
        for (tp in fn.typeParameters) tp.extendsBound?.text?.let { b -> bounds.getOrPut(tp.name ?: continue) { ArrayList() } += b }
        for (c in fn.typeConstraints) {
            val name = c.subjectTypeParameterName?.getReferencedName() ?: continue
            c.boundTypeReference?.text?.let { b -> bounds.getOrPut(name) { ArrayList() } += b }
        }
        if (bounds.isEmpty()) return true
        fun admits(param: String, t: KotlinType): Boolean = bounds[param].orEmpty().all { text ->
            val bound = runCatching { service.typeFromText(text, resolver.fileContext) }.getOrNull() ?: return@all true
            if (bound.isTypeParameter || t.isTypeParameter || bound.qualifiedName == "kotlin.Any") return@all true
            t.qualifiedName == bound.qualifiedName || runCatching { bound.isAssignableFrom(t) }.getOrDefault(true)
        }
        val receiver = fn.receiverTypeReference?.typeElement as? KtUserType ?: return true
        val receiverName = receiver.referencedName ?: return true
        // `fun <T : B> T.ext()`: the receiver itself must fit T's bounds.
        if (receiver.qualifier == null && receiver.typeArguments.isEmpty() && receiverName in bounds) return admits(receiverName, recv)
        // `fun <S : Base> Some<S>.ext()` on a `Some<…>`: each argument standing for a bounded parameter must fit it.
        if (receiverFqn != recv.qualifiedName) return true
        receiver.typeArguments.forEachIndexed { i, projection ->
            val param = (projection.typeReference?.typeElement as? KtUserType)?.takeIf { it.qualifier == null }?.referencedName
                ?: return@forEachIndexed
            if (param !in bounds) return@forEachIndexed
            val actual = recv.typeArguments.getOrNull(i) as? KotlinType ?: return@forEachIndexed
            if (actual.projection == "*") return@forEachIndexed
            if (!admits(param, actual)) return false
        }
        return true
    }

    /** The top-level extension function of this file that [s] stands for: same name, same receiver classifier. */
    private fun sameFileExtension(s: KotlinSymbol, resolver: KotlinResolver): KtNamedFunction? {
        val receiverSimple = s.receiverTypeFqn?.substringAfterLast('.') ?: s.receiverTypeParam ?: return null
        return resolver.ktFile.declarations.firstOrNull { d ->
            d is KtNamedFunction && d.name == s.name &&
                ((d.receiverTypeReference?.typeElement as? KtUserType)?.referencedName.let { it == receiverSimple || it == s.receiverTypeParam || (s.receiverTypeFqn == "kotlin.Any" && it != null) })
        } as? KtNamedFunction
    }

    /**
     * `invoke` on a value of function type (`f.invoke(1)`, and the only way to call a nullable one:
     * `f?.invoke(1)`). A function type is synthetic, so its one member comes from nowhere but here.
     */
    private fun functionTypeInvoke(type: KotlinType): KotlinSymbol? {
        if (!TypeRendering.isFunctionType(type.qualifiedName) || type.typeArguments.isEmpty()) return null
        val args = type.typeArguments
        if (args.any { it.qualifiedName == "kotlin.coroutines.Continuation" }) return null
        val params = args.dropLast(1).drop(if (type.isExtensionFunctionType) 1 else 0)
        val result = args.last()
        fun show(t: TypeRef) = (t as? KotlinType)?.toString() ?: t.qualifiedName.substringAfterLast('.')
        val names = params.indices.map { "p${it + 1}" }
        return KotlinSymbol(
            name = "invoke", kind = SymbolKind.METHOD, type = result,
            signature = "(" + params.indices.joinToString(", ") { "${names[it]}: ${show(params[it])}" } + "): ${show(result)}",
            paramTypes = params, paramNames = names, paramHasDefault = params.map { false },
            declaringClassFqn = type.qualifiedName,
        )
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
        val raw = (members + scopeExt + importableSingletonExtensions(recvType, prefix, resolver))
            .filter { it.kind == SymbolKind.METHOD && it.isInfix }
        return PositionResult(raw, keywordContext = true, infixInsert = true)
    }

    /** A bare name in expression/statement position: scope symbols + visible types, plus the override-stub,
     *  named-argument, and expected-type extras, and the keyword/live-template context. */
    private fun nameReferenceCandidates(
        markerLeaf: KtElement?,
        offset: Int,
        matcher: PrefixMatcher,
        resolver: KotlinResolver,
        autoImport: KotlinAutoImport,
        widened: Boolean = false,
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
        val overridePosition = isOverridePosition(markerLeaf) &&
            (markerLeaf?.parent as? KtCallableDeclaration)?.receiverTypeReference == null
        if (overridePosition) {
            val (indent, leadingDelete) = overrideInsertContext(markerLeaf, offset)
            // The declaration whose name is being written (`override fun ov<caret>`) takes only a member of its own
            // kind (IntelliJ's `canCompleteDeclarationWithMember`). At a bare word an item also answers to
            // `override`, its other lookup string (`OverridesCompletionLookupElementDecorator`).
            val decl = (markerLeaf?.parent as? KtCallableDeclaration)?.takeIf { it.nameIdentifier === markerLeaf }
            val byKeyword = decl == null && overrideKeywordMatches(prefix, matcher)
            resolver.overridableMembersAt(offset)
                .filter { (byKeyword || matcher.matches(it.name)) && (decl == null || canCompleteDeclarationWith(decl, it)) }
                // IntelliJ's `PreferAbstractForOverrideWeigher`: what must be implemented before what may be.
                .sortedBy { if (isAbstractMember(it)) 0 else 1 }
                .forEach { extra += KotlinCompletionItems.overrideItem(it, indent, leadingDelete, isVar = isMutableProperty(it)) }
            // A bare word in a class body is a member being declared: IntelliJ offers the keywords and the
            // overrides there, never the values and types in scope.
            if (decl == null && markerLeaf?.parent !is KtNamedDeclaration && climbTo<KtClassBody>(markerLeaf) != null) {
                return PositionResult(emptyList(), extra = extra, keywordContext = true)
            }
        }
        // Primary-constructor parameter position → offer the inherited open PROPERTIES as `override val name: T`
        // stubs (a ctor parameter can override a supertype property, never a function). When `override` is
        // already typed on the parameter the stub omits it (the keyword token is likewise suppressed).
        if (isPrimaryCtorParamPosition(markerLeaf)) {
            val param = climbTo<KtParameter>(markerLeaf)
            val overrideTyped = param?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true
            // `override val <caret>`: a `val` cannot override a `var`.
            val valTyped = param?.valOrVarKeyword?.text == "val"
            val byKeyword = !overrideTyped && overrideKeywordMatches(prefix, matcher)
            resolver.overridableMembersAt(offset)
                .filter { it.kind == SymbolKind.FIELD && (byKeyword || matcher.matches(it.name)) }
                .filter { !(valTyped && isMutableProperty(it)) }
                .sortedBy { if (isAbstractMember(it)) 0 else 1 }
                .forEach { extra += KotlinCompletionItems.ctorOverrideParam(it, overrideTyped, isVar = isMutableProperty(it)) }
        }
        // Inside a call's argument list → offer the callee's not-yet-supplied parameter names. When the caret is
        // on the NAME of an argument that is ALREADY named (`foo(contai|nerColor = x)`), the ` = ` is already
        // present, so insert the bare name (the editor replaces the whole name token) — no duplicated `=`. The
        // arg being edited carries the marker, so its (now-garbled) name never matches a real parameter and so
        // doesn't suppress its own re-completion.
        // The arguments still to pass, several at once, from same-named values (`foo(a, b, c)`).
        extra += multipleArgumentItems(markerLeaf, offset, resolver)
        namedArgCallOf(markerLeaf)?.let { call ->
            val named = namedArgumentItems(call, markerLeaf, offset, matcher, resolver)
            // Named arguments are what is being written once the list names one or spans lines.
            val list = call.valueArgumentList
            val preferNamed = list != null && (list.arguments.any { it.getArgumentName() != null } || list.text.contains('\n'))
            extra += KotlinCompletionWeighing.placeNamedArguments(named.items, preferNamed)
            // Past a named argument out of its own position only another named one may follow: nothing else fits.
            if (named.onlyNamed) return PositionResult(emptyList(), extra = extra)
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
        // The one parameter of a property setter (`set(<caret>)`) → `value`, the name everyone gives it.
        setterParameterItem(markerLeaf, matcher)?.let { extra += it }
        // The marker IS the NAME being given to a declaration (`val foo`, `fun bar`, a parameter) — an
        // identifier the user is inventing, so scope symbols / type names / auto-imports don't belong here.
        // (Any override / ctor-property / named-argument stubs above still apply: they're the *right* offers at
        // a member/ctor-param name spot.) A value/function/class/typealias name admits no keywords either (the
        // declaring keyword is already typed); a parameter / type-parameter name keeps its modifiers
        // (`val`/`var`/`vararg`/`reified`), so keyword context stays on there.
        val declName = declarationNameKind(markerLeaf)
        // A function or constructor parameter being named: `name: Type` pairs, from the names other parameters
        // in this file already use and from the type names the typed prefix fits (`fun f(b|` -> `bar: FooBar`).
        if (declName == DeclNameKind.PARAM) extra += parameterNameAndTypeItems(markerLeaf, prefix, resolver, autoImport)
        // The rest of a lambda's parameter list being written (`{ foo, <caret> }` -> `bar -> `).
        if (declName == DeclNameKind.PARAM) extra += remainingLambdaParameterItems(markerLeaf, matcher, resolver)
        // An untyped `lateinit var` is named with its type too.
        if (declName == DeclNameKind.DECL) extra += parameterNameAndTypeItems(markerLeaf, prefix, resolver, autoImport)
        // The name of an `operator fun` that can be one (a member, or an extension): the operator conventions.
        if (declName == DeclNameKind.DECL) extra += operatorNameItems(markerLeaf, matcher)
        if (declName == DeclNameKind.DECL) KotlinKeywords.funInterfaceItem(markerLeaf, prefix)?.let { extra += it }
        // The names of unresolved references nearby, and a top-level class's file name.
        if (declName != DeclNameKind.NONE) extra += KotlinDeclarationNameItems(service, resolver).items(markerLeaf, offset, matcher, resolver.fileContext.path)
        if (declName != DeclNameKind.NONE) {
            // …with ONE exception: a `fun`/`val` name may be preceded by a RECEIVER TYPE (`fun String.shout()`,
            // `val List<T>.second`), which is the only way to declare an extension. So type names do belong at
            // `fun <caret>`; nothing offered them, leaving only the buffer-word guesses, with no way to reach
            // (or auto-import) the receiver type from the popup. See [receiverTypeCandidates] for the gate.
            val types = receiverTypeCandidates(markerLeaf, declName, prefix, resolver, offset)
            return PositionResult(
                types?.symbols.orEmpty(), extra = extra,
                // After a parameter's `val`/`var` only its name can follow: no modifier keyword is left to offer.
                keywordContext = declName == DeclNameKind.PARAM && (markerLeaf?.parent as? KtParameter)?.valOrVarKeyword == null,
                capped = types?.capped == true, typeInsert = types != null,
            )
        }
        // The first word of a lambda that declares no parameters may be its parameter list (`{ entry -> `).
        extra += lambdaSignatureItems(markerLeaf, matcher, resolver)
        // The type the context wants → offer literals/enum constants and rank assignable candidates first.
        // In an annotation's array literal the element is what is written, not the array the argument is.
        // A getter's expression body and a labeled return are read first: the resolver's walk does not type them.
        val expected = annotationArrayElementType(markerLeaf, resolver)
            ?: KotlinCompletionWeighing.accessorOrLabeledReturnExpectedType(climbTo<KtNameReferenceExpression>(markerLeaf), resolver) { l ->
                explicitLabel(l) ?: lambdaImplicitLabel(l)
            }
            ?: resolver.expectedTypeAt(offset)
            ?: KotlinCompletionWeighing.operandExpectedType(climbTo<KtNameReferenceExpression>(markerLeaf), resolver)
        expected?.let { extra += expectedExtras(it, matcher, autoImport) }
        val types = KotlinPerf.span("typeNames") { service.typeNameCandidates(prefix, currentFilePath = resolver.fileContext.path) }
        // At an empty prefix the index is not asked for names, but the classes the file imports by name are in scope.
        val importedTypes = if (prefix.isEmpty()) service.explicitlyImportedTypes(resolver.fileContext) else emptyList()
        // The root packages too: an expression may start with a fully-qualified name (`kotlin.math.PI`).
        // The local scope matched as IntelliJ matches it, at any word of a name (`ba` finds `fooBar`).
        val wordMatcher = KotlinNameMatcher(prefix)
        val scope = KotlinPerf.span("scope") { withoutHiddenAccessors(resolver.scopeSymbolsAt(offset, prefix, localNameFilter = wordMatcher::matches), prefix) } +
            contextParametersInScope(markerLeaf, matcher, resolver) + implicitReceiverSingletonExtensions(offset, prefix, resolver)
        val aliases = importAliases(resolver, matcher, typesOnly = false)
        // The second pass (see [completeInner]): what an import of an object or a Java class would bring in.
        val statics = if (widened) service.staticCallablesByName(prefix, resolver.fileContext.path, resolver.fileContext.packageName, matcher::matches) else emptyList()
        val raw = withContextArguments(scope, markerLeaf, offset, resolver) + (if (importedTypes.isEmpty()) types.symbols else types.symbols + importedTypes) +
            defaultScopeMiddleMatches(prefix) + service.typeAliasCandidates(prefix) +
            aliases + service.knownRootPackages(prefix) + statics.map { it.first }
        return PositionResult(
            raw, extra = extra, expected = expected, keywordContext = true,
            nameReference = true, capped = types.capped, aliased = aliases.toHashSet(),
            importVia = if (statics.isEmpty()) emptyMap() else statics.toMap(),
        )
    }

    /**
     * The parameter lists a lambda can declare (`foo, bar -> `), offered at the first word of a lambda that
     * declares none (`42.foo { <caret> }`), as IntelliJ offers them: the names the parameter's function type
     * gives (`block: (reference: T) -> Unit`), else names made from the parameter types (`entry`/`mapEntry`
     * for a `Map.Entry`, `i, i1` for two `Int`s), and for one parameter of a project `data class` its
     * destructuring (`(foo, bar)`). The types come from the shape the lambda is expected to have.
     */
    private fun lambdaSignatureItems(leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver): List<CompletionItem> {
        val nameRef = climbTo<KtNameReferenceExpression>(leaf) ?: return emptyList()
        val body = nameRef.parent as? KtBlockExpression ?: return emptyList()
        val literal = body.parent as? KtFunctionLiteral ?: return emptyList()
        val lambda = literal.parent as? KtLambdaExpression ?: return emptyList()
        if (literal.arrow != null || literal.valueParameters.isNotEmpty() || body.statements.firstOrNull() !== nameRef) return emptyList()
        val shape = runCatching { resolver.expectedLambdaShape(lambda) }.getOrNull() ?: return emptyList()
        val types = shape.parameterTypes.map { it as? KotlinType ?: return emptyList() }
        if (types.isEmpty() || types.any { it.isTypeParameter }) return emptyList()
        val out = ArrayList<CompletionItem>()
        fun item(names: List<String>, shown: List<KotlinType>, destructuring: Boolean = false) {
            if (names.isEmpty() || !matcher.matches(names.first())) return
            val text = names.joinToString(", ").let { if (destructuring) "($it)" else it }
            out += CompletionItem(
                label = text,
                insertText = "$text -> ",
                kind = CompletionItemKind.PARAMETER,
                detail = shown.singleOrNull()?.toString() ?: shown.joinToString(", ", "(", ")"),
                sortPriority = -1,
            )
        }
        val declared = declaredLambdaParameterNames(lambda, resolver)
        if (declared != null && declared.size == types.size) {
            item(declared, types)
            return out
        }
        if (types.size == 1) {
            for (name in typeNameSuggestions(types[0])) item(listOf(name), types)
            val components = dataClassComponents(types[0]) ?: operatorComponents(types[0])
            // One component is written without parentheses (`i -> `), as `createCompoundLookupElement` writes it.
            if (components != null) item(components.map { it.first }, components.map { it.second }, destructuring = components.size > 1)
            return out
        }
        lambdaParameterNames(types)?.let { item(it, types) }
        return out
    }

    /** A name for each of [types] made from the type, unique among themselves (`i`, `i1`); null when a type
     *  gives none. */
    private fun lambdaParameterNames(types: List<KotlinType>): List<String>? {
        val used = HashSet<String>()
        return types.map { t ->
            val base = typeNameSuggestions(t).lastOrNull() ?: return null
            var name = base
            var n = 1
            while (!used.add(name)) name = base + n++
            name
        }
    }

    /**
     * The parameters still to name in a lambda whose list is being written (`{ foo, <caret> }`, no arrow yet):
     * the rest of the expected shape's parameters, by the names its function type declares or made from their
     * types, inserted with the arrow. Nothing once every parameter is named, or when the names written so far
     * are not the suggested ones.
     */
    private fun remainingLambdaParameterItems(leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver): List<CompletionItem> {
        val param = climbTo<KtParameter>(leaf) ?: return emptyList()
        val list = param.parent as? KtParameterList ?: return emptyList()
        val literal = list.parent as? KtFunctionLiteral ?: return emptyList()
        val lambda = literal.parent as? KtLambdaExpression ?: return emptyList()
        if (literal.arrow != null) return emptyList()
        val index = list.parameters.indexOf(param)
        if (index < 1 || param.typeReference != null) return emptyList()
        val shape = runCatching { resolver.expectedLambdaShape(lambda) }.getOrNull() ?: return emptyList()
        val types = shape.parameterTypes.map { it as? KotlinType ?: return emptyList() }
        if (types.size <= index || types.any { it.isTypeParameter }) return emptyList()
        // Only while the list written so far is the suggested one: names of the writer's own (`{ acc, <caret> }`)
        // say they are not following it.
        val written = list.parameters.take(index).map { it.name ?: return emptyList() }
        val all = declaredLambdaParameterNames(lambda, resolver)?.takeIf { it.size == types.size }
            ?: lambdaParameterNames(types)
            ?: return emptyList()
        if (all.take(index) != written) return emptyList()
        val names = all.drop(index)
        if (!matcher.matches(names.first())) return emptyList()
        val rest = types.drop(index)
        val text = names.joinToString(", ")
        return listOf(
            CompletionItem(
                label = text,
                insertText = "$text -> ",
                kind = CompletionItemKind.PARAMETER,
                detail = rest.singleOrNull()?.toString() ?: rest.joinToString(", ", "(", ")"),
                sortPriority = -1,
            )
        )
    }

    /** The parameter names the function type of the parameter [lambda] fills declares (`(foo: T, bar: U) ->`),
     *  when the callee is a project function and names every one; null otherwise. */
    private fun declaredLambdaParameterNames(lambda: KtLambdaExpression, resolver: KotlinResolver): List<String>? {
        val (call, argIndex) = runCatching { resolver.enclosingCallAndParamIndex(lambda) }.getOrNull() ?: return null
        val callee = runCatching { resolver.resolveCalleeFunction(call) }.getOrNull() ?: return null
        val fn = (callee.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return null
        val param = fn.valueParameters.getOrNull(resolver.lambdaParamIndex(call, argIndex, callee)) ?: return null
        var type = param.typeReference?.typeElement
        if (type is KtNullableType) type = type.innerType
        val names = (type as? KtFunctionType)?.parameters?.map { it.name ?: return null } ?: return null
        return names.takeIf { it.isNotEmpty() }
    }

    /** The names a value of type [t] is commonly given, shortest first: `i` for an `Int`, `ints` for a
     *  `List<Int>`, the trailing words of the class name otherwise (`entry`, `mapEntry` for `Map.Entry`). */
    private fun typeNameSuggestions(t: KotlinType): List<String> {
        val q = t.qualifiedName
        PRIMITIVE_PARAMETER_NAMES[q]?.let { return listOf(it) }
        fun words(fqn: String): List<String> {
            val classes = fqn.split('.').dropWhile { it.firstOrNull()?.isUpperCase() != true }
            return classes.flatMap { c -> CAMEL_WORD.findAll(c).map { it.value }.toList() }
        }
        if (q in PLURAL_TYPES) {
            val element = t.typeArguments.firstOrNull() as? KotlinType ?: return emptyList()
            if (element.isTypeParameter) return emptyList()
            val last = element.qualifiedName.substringAfterLast('.')
            return listOf(last.replaceFirstChar { it.lowercaseChar() } + "s")
        }
        val ws = words(q)
        if (ws.isEmpty()) return emptyList()
        return ws.indices.reversed().map { i -> ws.subList(i, ws.size).joinToString("").replaceFirstChar { it.lowercaseChar() } }
    }

    /**
     * The destructuring of a value of type [t] through its `operator fun componentN()` functions, members or
     * extensions (`K2TrailingFunctionParameterNameCompletionContributorBase.getComponents`): `component1` up to the
     * last one there is, each named by what its body returns (`= key`), else after its type (`i` for an `Int`).
     * Null for a `List` (its components are positional, not names) and where there is no `component1`.
     */
    private fun operatorComponents(t: KotlinType): List<Pair<String, KotlinType>>? {
        if (t.isTypeParameter || service.typeByFqn("kotlin.collections.List")?.isAssignableFrom(t) == true) return null
        var out: ArrayList<Pair<String, KotlinType>>? = null
        val used = HashSet<String>()
        for (i in 1..MAX_COMPONENTS) {
            val fn = service.membersNamed(t.qualifiedName, t.typeArguments, "component$i")
                .firstOrNull { it.kind == SymbolKind.METHOD && it.paramNames.isEmpty() && isOperatorFunction(it) } ?: break
            val type = fn.type as? KotlinType ?: return null
            if (type.isTypeParameter) return null
            val base = componentNameFromBody(fn)
                ?: STDLIB_COMPONENT_NAMES[fn.receiverTypeFqn ?: t.qualifiedName]?.getOrNull(i - 1)
                ?: typeNameSuggestions(type).lastOrNull()
                ?: return null
            var name = base
            var n = 1
            while (!used.add(name)) name = base + n++
            (out ?: ArrayList<Pair<String, KotlinType>>(4).also { out = it }) += name to type
        }
        return out
    }

    /** The name a `componentN` returns (`fun component1() = key`, `{ return key }`), or its parameter's for a
     *  data class property; null when its source says nothing. */
    private fun componentNameFromBody(fn: KotlinSymbol): String? {
        val psi = (runCatching { fn.declaration() }.getOrNull() as? KotlinDomNode)?.psi ?: return null
        if (psi is KtParameter) return psi.name
        val f = psi as? KtNamedFunction ?: return null
        val body = f.bodyExpression
        val returned = when (body) {
            is KtBlockExpression -> (body.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
            else -> body
        }
        return (returned as? KtNameReferenceExpression)?.getReferencedName()
    }

    /** A project `data class`'s primary-constructor properties with their types, or null for anything else. */
    private fun dataClassComponents(t: KotlinType): List<Pair<String, KotlinType>>? {
        val cls = (runCatching { service.classDeclaration(t.qualifiedName) }.getOrNull() as? KotlinDomNode)?.psi as? KtClassOrObject
            ?: return null
        if (!cls.isData()) return null
        val ctx = runCatching { service.sourceClass(t.qualifiedName)?.ctx }.getOrNull()
        val props = cls.primaryConstructorParameters.filter { it.hasValOrVar() }
        if (props.isEmpty()) return null
        return props.map { p ->
            val type = service.typeFromText(p.typeReference?.text, ctx) ?: return null
            (p.name ?: return null) to type
        }
    }

    /**
     * What this file imports under an alias (`import java.io.File as JFile`, `import kotlin.collections.listOf
     * as list`) and [matcher] fits, by the alias: the class, or the top-level functions and properties of that
     * name ([typesOnly] keeps the classes). An aliased name is in scope only under its alias, which nothing
     * else offers. Extensions are left to the receiver's member list.
     */
    private fun importAliases(resolver: KotlinResolver, matcher: PrefixMatcher, typesOnly: Boolean): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        for (imp in resolver.ktFile.importDirectives) {
            val alias = imp.aliasName ?: continue
            if (imp.isAllUnder || alias.contains(MARKER) || !matcher.matches(alias)) continue
            val fqn = imp.importedFqName?.asString()?.takeIf { '.' in it } ?: continue
            if (runCatching { service.isKnownType(fqn) }.getOrDefault(false)) {
                out += KotlinSymbol(alias, SymbolKind.CLASS, service.typeByFqn(fqn))
                continue
            }
            if (typesOnly) continue
            val name = fqn.substringAfterLast('.')
            runCatching { service.packageMembers(fqn.substringBeforeLast('.'), name) }.getOrDefault(emptyList())
                .filter { it.name == name && !it.isExtension && (it.kind == SymbolKind.METHOD || it.kind == SymbolKind.FIELD) }
                .forEach { out += it.renamed(alias) }
        }
        return out
    }

    /** Whether [leaf] is in the argument list of an annotation (`@A(<caret>)`, `@A(x.<caret>)`). */
    private fun insideAnnotationArguments(leaf: KtElement?): Boolean {
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtValueArgumentList -> return n.parent is KtAnnotationEntry
                is KtDeclaration, is KtBlockExpression, is KtLambdaExpression -> return false
            }
            n = n.parent
        }
        return false
    }

    /**
     * The candidates that can stand in a compile-time constant, as IntelliJ filters an annotation's arguments:
     * `const` properties, enum entries, the array factories whose array fits the [expected] type, the operators
     * the compiler folds on a constant's value (`length`, `not`, `plus`, the conversions), and the classifiers
     * and packages that qualify them. A local, a plain property, a function and an extension cannot. A library
     * property is kept, its constness not being recorded on the symbol. [receiverFqn] is the type of a
     * `receiver.` access, whose members are judged as its.
     */
    private fun compileTimeConstants(
        raw: List<KotlinSymbol>, expected: KotlinType?, receiverFqn: String?, resolver: KotlinResolver,
    ): List<KotlinSymbol> {
        val properties by lazy {
            val byName = HashMap<String, MutableList<KtProperty>>()
            fun visit(e: KtElement) {
                if (e is KtProperty) e.name?.let { byName.getOrPut(it) { ArrayList(1) } += e }
                if (e is KtBlockExpression) return
                for (c in e.children) visit(c)
            }
            visit(resolver.ktFile)
            byName
        }
        fun isConst(p: KtProperty) = p.hasModifier(KtTokens.CONST_KEYWORD)
        return raw.filter { s ->
            when (s.kind) {
                SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER -> false
                SymbolKind.METHOD -> {
                    // A built-in's members carry no declaring class; they are the receiver's (`flag.not()`).
                    val owner = s.declaringClassFqn ?: receiverFqn
                    when {
                        s.isExtension -> false
                        s.packageName == "kotlin" && s.name in ARRAY_FACTORIES -> {
                            val returned = (s.type as? KotlinType)?.qualifiedName
                            expected == null || returned == null || expected.isTypeParameter ||
                                expected.qualifiedName == "kotlin.Any" || returned == expected.qualifiedName
                        }
                        owner != null && owner in CONSTANT_TYPES -> s.name in CONSTANT_OPERATORS
                        else -> false
                    }
                }
                SymbolKind.FIELD -> when {
                    s.isExtension -> false
                    (s.declaringClassFqn ?: receiverFqn) in CONSTANT_TYPES -> s.name == "length"
                    s.origin.fromSource -> {
                        val decl = (s.declaration() as? KotlinDomNode)?.psi as? KtProperty
                        if (decl != null) isConst(decl)
                        else properties[s.name]?.let { ps -> ps.any(::isConst) } ?: true
                    }
                    else -> true
                }
                else -> true
            }
        }
    }

    /**
     * [scope] without the project functions and properties whose context parameters (`context(_: String) fun
     * f()`) nothing at [offset] can fill: a call passes each one implicitly from an implicit receiver or an
     * enclosing declaration's own context parameter, and IntelliJ does not offer the callable where none fits.
     * Only what is judged for sure is removed: a context type that does not resolve keeps the candidate.
     */
    private fun withContextArguments(
        scope: List<KotlinSymbol>, leaf: KtElement?, offset: Int, resolver: KotlinResolver,
    ): List<KotlinSymbol> {
        var available: List<KotlinType>? = null
        fun available(): List<KotlinType> = available ?: run {
            val out = ArrayList<KotlinType>()
            runCatching { out += resolver.implicitReceiversAt(offset) }
            forEachAncestor(leaf) { n ->
                val decl = n as? KtDeclaration ?: return@forEachAncestor
                for (ref in contextTypeReferences(decl)) {
                    runCatching { service.typeFromText(ref.text, resolver.fileContext) }.getOrNull()?.let { out += it }
                }
            }
            out
        }.also { available = it }
        fun fits(decl: KtCallableDeclaration): Boolean {
            val wanted = contextTypeReferences(decl)
            return wanted.isEmpty() || wanted.all { ref -> contextArgumentFits(ref, decl, available(), resolver) }
        }
        // A symbol read back from the source index carries no declaration; this file's own is found by name,
        // and kept when any declaration of that name can be called here.
        val topLevel by lazy { resolver.ktFile.declarations.filterIsInstance<KtCallableDeclaration>().groupBy { it.name } }
        return scope.filter { s ->
            if (!s.origin.fromSource || (s.kind != SymbolKind.METHOD && s.kind != SymbolKind.FIELD)) return@filter true
            val decl = (s.declaration() as? KotlinDomNode)?.psi as? KtCallableDeclaration
            if (decl != null) return@filter fits(decl)
            if (s.owner != null || s.isExtension) return@filter true
            val named = topLevel[s.name] ?: return@filter true
            named.any { fits(it) }
        }
    }

    /**
     * The member extensions of project objects that an import would bring in and that apply to [recv]
     * ([KotlinResolver.importableSingletonExtensions]), with those declared on `Any`, which apply to every
     * receiver, judged like any other extension.
     */
    private fun importableSingletonExtensions(recv: KotlinType, prefix: String, resolver: KotlinResolver): List<KotlinSymbol> {
        val anyReceiver = if (recv.isTypeParameter) emptyList()
        else service.importableSingletonExtensions(setOf("kotlin.Any"), prefix).map { resolver.bindMemberExtensionReceiver(it, recv) }
        return (resolver.importableSingletonExtensions(recv, prefix) + anyReceiver).filter { extensionAppliesTo(it, recv, resolver) }
    }

    /**
     * What [importableSingletonExtensions] offers on each implicit receiver at [offset] (`fun T.usage() { foo<caret> }`
     * reaches `object A { fun T.fooExtension() }`): a bare name calls an extension on `this` as well. Read from
     * the project's source model, so a position without implicit receivers costs nothing.
     */
    private fun implicitReceiverSingletonExtensions(offset: Int, prefix: String, resolver: KotlinResolver): List<KotlinSymbol> {
        val receivers = runCatching { resolver.implicitReceiversAt(offset) }.getOrDefault(emptyList())
        if (receivers.isEmpty()) return emptyList()
        return receivers.flatMap { importableSingletonExtensions(it, prefix, resolver) }
    }

    /**
     * The named context parameters of the declarations around [leaf] (`context(logger: Logger) fun log()`):
     * values in scope in the body like its parameters, which the scope walk does not list. An unnamed `_` one
     * is no value.
     */
    private fun contextParametersInScope(leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        forEachAncestor(leaf) { n ->
            val decl = n as? KtDeclaration ?: return@forEachAncestor
            // An accessor's context is its property's.
            val owner = if (decl is KtPropertyAccessor) decl.parent as? KtDeclaration ?: decl else decl
            val list = contextListOf(owner) ?: return@forEachAncestor
            for (p in list.contextParameters) {
                val name = p.name ?: continue
                if (name == "_" || !matcher.matches(name)) continue
                val type = runCatching { service.typeFromText(p.typeReference?.text, resolver.fileContext) }.getOrNull()
                out += KotlinSymbol(name, SymbolKind.PARAMETER, type, origin = SymbolOrigin(fromSource = true, file = null))
            }
        }
        return out
    }

    /**
     * The type of a receiver rooted at a named context parameter (`second.`, `holder.box.`), which the resolver's
     * scope does not list: the parameter's declared type, then each further name read as a property of the type
     * before it. Null for any other shape. One exact member lookup per segment.
     */
    private fun contextParameterPathType(receiver: KtExpression, resolver: KotlinResolver): KotlinType? {
        val names = ArrayList<String>()
        var e: KtExpression? = receiver
        while (true) {
            while (e is KtParenthesizedExpression) e = e.expression
            when (e) {
                is KtDotQualifiedExpression -> {
                    names += (e.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
                    e = e.receiverExpression
                }
                is KtNameReferenceExpression -> { names += e.getReferencedName(); break }
                else -> return null
            }
        }
        names.reverse()
        val root = contextParametersInScope(receiver, PrefixMatcher(names[0]), resolver).firstOrNull { it.name == names[0] }
        var type = root?.type as? KotlinType ?: return null
        for (name in names.drop(1)) {
            val member = service.membersForCompletion(type.qualifiedName, type.typeArguments, name)
                .firstOrNull { it.name == name && it.kind == SymbolKind.FIELD } ?: return null
            type = member.type as? KotlinType ?: return null
        }
        return type
    }

    private fun contextListOf(decl: KtDeclaration): KtContextReceiverList? =
        (decl.children.asSequence() + decl.modifierList?.children.orEmpty().asSequence())
            .firstOrNull { it is KtContextReceiverList } as? KtContextReceiverList

    /** The type references of [decl]'s `context(...)` list, empty when it has none. */
    private fun contextTypeReferences(decl: KtDeclaration): List<KtTypeReference> {
        val list = contextListOf(decl) ?: return emptyList()
        return list.contextParameters.mapNotNull { it.typeReference } + list.typeReferences
    }

    /** Whether one of the [available] context values fits the context parameter typed [ref] of [decl]. */
    private fun contextArgumentFits(
        ref: KtTypeReference, decl: KtCallableDeclaration, available: List<KotlinType>, resolver: KotlinResolver,
    ): Boolean {
        val text = ref.text.trim()
        val nullable = text.endsWith("?")
        // A context parameter typed by the declaration's own type parameter takes any value its bound admits.
        val typeParameter = decl.typeParameters.firstOrNull { it.name == text.removeSuffix("?") }
        val boundText = if (typeParameter != null) typeParameter.extendsBound?.text ?: return available.isNotEmpty() else text
        val want = runCatching { service.typeFromText(boundText, resolver.fileContext) }.getOrNull() ?: return true
        if (want.isTypeParameter || !runCatching { service.isKnownType(want.qualifiedName) }.getOrDefault(false)) return true
        return available.any { a ->
            (nullable || want.nullable || !a.nullable) && runCatching { want.isAssignableFrom(a) }.getOrDefault(true)
        }
    }

    /**
     * `value` at the name of a property setter's parameter (`set(<caret>)`, `set(va<caret>)`). The name is free,
     * but `value` is the convention, and nothing else is offered here: the slot is a name being invented, so
     * without this the popup held only words lifted from the buffer (`var` for someone typing `va`).
     */
    private fun setterParameterItem(markerLeaf: KtElement?, matcher: PrefixMatcher): CompletionItem? {
        val param = markerLeaf?.parent as? KtParameter ?: return null
        if (param.nameIdentifier !== markerLeaf) return null
        val accessor = (param.parent as? KtParameterList)?.parent as? KtPropertyAccessor ?: return null
        if (!accessor.isSetter || !matcher.matches(SETTER_PARAMETER)) return null
        return CompletionItem(
            label = SETTER_PARAMETER,
            insertText = SETTER_PARAMETER,
            kind = CompletionItemKind.PARAMETER,
            sortPriority = -1,
        )
    }

    /**
     * The other declarations whose name is written with its type, as a parameter's is: an untyped `lateinit
     * var` (`lateinit var f<caret>`) and a context parameter, which while its name alone is written parses as
     * a context receiver's type (`context(f<caret>)`). Null anywhere else.
     */
    private fun nameAndTypeDeclarationAt(leaf: KtElement?): KtElement? {
        val property = leaf?.parent as? KtProperty
        if (property != null && property.nameIdentifier === leaf) {
            // Any property being named (`K2VariableOrParameterNameWithTypeCompletionContributor` reaches it through
            // the receiver-type dummy `val f<caret>.f`); only a `lateinit` one gets the type inserted too.
            return property.takeIf { it.typeReference == null && it.receiverTypeReference == null }
        }
        val type = climbTo<KtUserType>(leaf)?.takeIf { it.qualifier == null } ?: return null
        val ref = type.parent as? KtTypeReference ?: return null
        // The receiver's type sits in a context-receiver node of the list, or right in it.
        return ref.takeIf { it.parent is KtContextReceiverList || it.parent?.parent is KtContextReceiverList }
    }

    /**
     * IntelliJ's parameter name-and-type completion, at the name of a value parameter of a named function or a
     * class/secondary constructor (not a lambda's, a setter's, a catch clause's or an annotation class's, where a
     * free `name: Type` pair is not what is written). Two sources, deduplicated by the inserted text:
     *  * the parameters other functions and constructors in this FILE declare, whose name fits the prefix
     *    (`strings: List<String>`), as written;
     *  * a TYPE whose name the prefix fits, named after it: each camel-case tail of the type name is a
     *    suggestion (`FooBar` -> `fooBar`, `bar`), matched by prefix or camel humps against the typed text. A
     *    typed head the user keeps is split off at an uppercase letter (`myB` -> `my` + `Bar`); the first split
     *    that matches wins, so `myFooF` names a `FooFaa` `myFooFaa`, never `myFooFooFaa`. Only with a typed
     *    prefix: with none, every type in the project would qualify.
     */
    private fun parameterNameAndTypeItems(
        markerLeaf: KtElement?,
        prefix: String,
        resolver: KotlinResolver,
        autoImport: KotlinAutoImport,
    ): List<CompletionItem> {
        val param: KtElement = markerLeaf?.parent as? KtParameter ?: nameAndTypeDeclarationAt(markerLeaf) ?: return emptyList()
        if (param is KtParameter) {
            if (param.typeReference != null) return emptyList()
            val owner = (param.parent as? KtParameterList)?.parent ?: return emptyList()
            val ok = when (owner) {
                is KtNamedFunction -> owner.name != null
                is KtPrimaryConstructor -> (owner.parent as? KtClass)?.isAnnotation() != true
                is KtSecondaryConstructor -> true
                else -> false
            }
            if (!ok) return emptyList()
        }
        val out = LinkedHashMap<String, CompletionItem>()
        // How each suggestion ranks, IntelliJ's order for them (`K2VariableOrParameterNameWithTypeCompletionContributor`
        // and the weighers before its `VariableOrParameterNameWithTypeWeigher`): a deprecated type last, then one
        // needing an import (a sibling of an imported one first), then the priority: a name and type this file's
        // parameters already use, the more often the earlier, before a name made from a type, the shorter the kept
        // user prefix the earlier; then by name.
        val rankOf = HashMap<String, IntArray>()
        fun fits(m: PrefixMatcher, name: String): Boolean =
            m.grade(name).let { it != null && it != PrefixMatcher.Grade.SUBSTRING }

        // From this file's other parameters (not a local function's: those are not part of the file's vocabulary).
        // A parameter typed with a type parameter that is not in scope here (another function's `T`, a class's
        // `T` from outside that class) is left out: its type would not resolve.
        val whole = PrefixMatcher(prefix)
        val visibleTypeParams = HashSet<String>()
        run {
            var n: KtElement? = param
            while (n != null) {
                (n as? KtTypeParameterListOwner)?.typeParameters?.forEach { tp -> tp.name?.let(visibleTypeParams::add) }
                n = n.parent
            }
        }
        (param.containingFile as? KtFile)?.let { kt ->
            fun visit(decls: List<KtDeclaration>, outerTypeParams: Set<String>) {
                for (d in decls) {
                    val declared = outerTypeParams + ((d as? KtTypeParameterListOwner)?.typeParameters?.mapNotNull { it.name }.orEmpty())
                    val hidden = declared - visibleTypeParams
                    val params = when (d) {
                        is KtNamedFunction -> d.valueParameters
                        is KtClass -> d.primaryConstructorParameters
                        is KtSecondaryConstructor -> d.valueParameters
                        else -> emptyList()
                    }
                    for (p in params) {
                        if (p === param) continue
                        val name = p.name ?: continue
                        val typeText = p.typeReference?.let(::parameterTypeText) ?: continue
                        if (MARKER in name || !fits(whole, name)) continue
                        if (hidden.any { tp -> Regex("\\b${Regex.escape(tp)}\\b").containsMatchIn(typeText) }) continue
                        val text = "$name: $typeText"
                        out.getOrPut(text) { paramNameTypeItem(text, null, emptyList()) }
                        rankOf.getOrPut(text) { intArrayOf(0, 0, 0, 0) }[3]--
                    }
                    if (d is KtClassOrObject) visit(d.declarations, declared)
                }
            }
            visit(kt.declarations, emptySet())
        }

        if (prefix.isNotEmpty()) {
            // Splits of the typed prefix into a kept head and a tail matched against a suggestion, shortest head
            // first: the whole prefix, then at each uppercase letter.
            val splits = buildList {
                add("" to prefix)
                for (i in 1 until prefix.length) if (prefix[i].isUpperCase()) add(prefix.substring(0, i) to prefix.substring(i))
            }
            val types = LinkedHashMap<String, KotlinSymbol>()
            for ((_, tail) in splits) {
                service.typeNameCandidates(tail.replaceFirstChar { it.uppercaseChar() }, currentFilePath = resolver.fileContext.path)
                    .symbols.forEach { s -> (s.type as? KotlinType)?.qualifiedName?.let { types.getOrPut(it) { s } } }
            }
            // A project type matches on any camel tail (`b` -> `FooBar`), which a name-index query cannot ask for.
            service.sourceTypeSymbols(resolver.fileContext.path)
                .forEach { s -> (s.type as? KotlinType)?.qualifiedName?.let { types.getOrPut(it) { s } } }
            for ((fqn, s) in types) {
                // Every classifier but an object (IntelliJ keeps annotation classes: `override: Override`).
                if (s.kind != SymbolKind.CLASS && s.kind != SymbolKind.INTERFACE && s.kind != SymbolKind.ENUM && s.kind != SymbolKind.ANNOTATION_TYPE) continue
                if (service.isObject(fqn)) continue
                val words = camelWords(s.name)
                if (words.isEmpty()) continue
                // `URLConnection` -> `urlConnection`: a leading acronym is lowercased whole.
                val suggestions = words.indices.map { i ->
                    val first = words[i]
                    val head = if (first.length > 1 && first.all { it.isUpperCase() }) first.lowercase()
                    else first.replaceFirstChar { it.lowercaseChar() }
                    head + words.subList(i + 1, words.size).joinToString("")
                }
                for ((head, tail) in splits) {
                    val m = PrefixMatcher(tail)
                    val hits = suggestions.filter { fits(m, it) }
                    if (hits.isEmpty()) continue
                    val generic = service.classTypeParameters(fqn).isNotEmpty()
                    for (h in hits) {
                        val name = if (head.isEmpty()) h else head + h.replaceFirstChar { it.uppercaseChar() }
                        val text = "$name: ${s.name}"
                        if (text in out) continue
                        val edit = importEditFor(s, autoImport)
                        out[text] = paramNameTypeItem(text, if (generic) "<>" else null, edit)
                        rankOf[text] = intArrayOf(
                            if (deprecatedForRanking(s)) 1 else 0,
                            if (edit.isEmpty()) 0 else 1,
                            if (edit.isEmpty()) 0 else autoImport.notImportedTier(fqn),
                            head.length,
                        )
                    }
                    break
                }
            }
        }
        val none = IntArray(4)
        val sorted = out.entries.sortedWith { a, b ->
            val ra = rankOf[a.key] ?: none
            val rb = rankOf[b.key] ?: none
            for (i in ra.indices) if (ra[i] != rb[i]) return@sortedWith ra[i] - rb[i]
            a.key.substringBefore(':').compareTo(b.key.substringBefore(':'))
        }
        // A property that is not `lateinit` takes only the name (`shouldInsertType`), so one suggestion per name, the
        // best ranked, shown with the type it was made from.
        if (param is KtProperty && !param.hasModifier(KtTokens.LATEINIT_KEYWORD)) {
            val names = HashSet<String>()
            return sorted.mapNotNull { (text, item) ->
                val name = text.substringBefore(':')
                if (!names.add(name)) return@mapNotNull null
                CompletionItem(
                    label = name,
                    insertText = name,
                    kind = CompletionItemKind.PARAMETER,
                    detail = text.substring(name.length),
                    sortPriority = -1,
                    additionalEdits = emptyList(),
                    relevance = item.relevance,
                )
            }
        }
        return sorted.map { it.value }
    }

    /** The operator-convention names (`plus`, `get`, `invoke`, …) at the name of an `operator fun` that is a
     *  member or an extension, each with the syntax it overloads as its detail. A top-level `operator fun`
     *  with no receiver can overload nothing, so it gets none. */
    private fun operatorNameItems(markerLeaf: KtElement?, matcher: PrefixMatcher): List<CompletionItem> {
        val fn = markerLeaf?.parent as? KtNamedFunction ?: return emptyList()
        if (!fn.hasModifier(KtTokens.OPERATOR_KEYWORD)) return emptyList()
        if (fn.receiverTypeReference == null && fn.parent !is KtClassBody) return emptyList()
        return OPERATOR_NAMES.filter { (name, _) -> matcher.matches(name) }.map { (name, syntax) ->
            CompletionItem(name, name, CompletionItemKind.METHOD, detail = syntax.ifEmpty { null }, sortPriority = -1)
        }
    }

    /** A `name: Type` item; a generic type inserts its `<>` with the caret inside, ready for the arguments. */
    private fun paramNameTypeItem(text: String, typeArgs: String?, imports: List<TextEdit>): CompletionItem =
        CompletionItem(
            label = text,
            insertText = text + typeArgs.orEmpty(),
            kind = CompletionItemKind.PARAMETER,
            sortPriority = -1,
            additionalEdits = imports,
            caret = if (typeArgs != null) CaretAction.At(text.length + 1) else CaretAction.AtEnd,
        )

    /** A parameter's type as written, without its type annotations (`@Foo Int` -> `Int`). */
    private fun parameterTypeText(ref: KtTypeReference): String =
        ref.text.replace(TYPE_ANNOTATIONS, "").trim()

    /** `FooBar` -> [Foo, Bar]; `URLConnection` -> [URL, Connection]. */
    private fun camelWords(name: String): List<String> {
        val words = ArrayList<String>()
        var start = 0
        for (i in 1 until name.length) {
            val boundary = name[i].isUpperCase() &&
                (!name[i - 1].isUpperCase() || (i + 1 < name.length && name[i + 1].isLowerCase()))
            if (boundary) {
                words += name.substring(start, i)
                start = i
            }
        }
        if (name.isNotEmpty()) words += name.substring(start)
        return words.filter { it.isNotEmpty() && it.first().isLetter() }
    }

    /** Whether the caret sits on the NAME identifier of a declaration, and if so which kind — so completion can
     *  suppress the scope/type candidates that never belong where the user is inventing an identifier. */
    private enum class DeclNameKind { NONE, DECL, PARAM }

    /** Classify [leaf] as a declaration-name spot: [DeclNameKind.DECL] for a value/function/class/object/
     *  typealias/destructuring-entry name (no keywords either), [DeclNameKind.PARAM] for a value- or
     *  type-parameter name (keeps its modifier keywords), else [DeclNameKind.NONE]. Keys on the leaf actually
     *  being the declaration's `nameIdentifier`, so a reference or initializer/type slot is never misread. */
    private fun declarationNameKind(leaf: KtElement?): DeclNameKind {
        val decl = leaf?.parent as? KtNamedDeclaration ?: return DeclNameKind.NONE
        if (decl.nameIdentifier !== leaf) return DeclNameKind.NONE
        return if (decl is KtParameter || decl is KtTypeParameter) DeclNameKind.PARAM else DeclNameKind.DECL
    }

    /** The type parameters the declarations around [leaf] introduce (a class's, a function's, a property's,
     *  innermost first), which a type written there may name: IntelliJ's scope classifiers include them. */
    private fun typeParametersAround(leaf: KtElement?, matcher: PrefixMatcher): List<KotlinSymbol> {
        var out: ArrayList<KotlinSymbol>? = null
        // A nested class or an object that is not `inner` sees none of the outer classes' type parameters.
        var outerClassesClosed = false
        forEachAncestor(leaf) { n ->
            val owner = n as? KtTypeParameterListOwner ?: return@forEachAncestor
            val cls = owner as? KtClassOrObject
            if (cls != null && outerClassesClosed) return@forEachAncestor
            if (cls != null && cls.parent is KtClassBody && !cls.hasModifier(KtTokens.INNER_KEYWORD)) outerClassesClosed = true
            if (cls is KtObjectDeclaration) outerClassesClosed = true
            for (tp in owner.typeParameters) {
                val name = tp.name ?: continue
                if (MARKER in name || !matcher.matches(name)) continue
                val list = out ?: ArrayList<KotlinSymbol>().also { out = it }
                if (list.none { it.name == name }) list += KotlinSymbol(name, SymbolKind.TYPE_PARAMETER, origin = SymbolOrigin(fromSource = true, file = null))
            }
        }
        return out ?: emptyList()
    }

    /**
     * The default-imported classifiers (`kotlin.*`, `java.lang.*`, …) the typed [prefix] reaches only past the
     * start of their name (`S` -> `IllegalStateException`): IntelliJ matches a SCOPE classifier anywhere in its
     * name, while the index is only asked for start matches (K2's `scopeNameFilter` against `getIndexNameFilter`).
     * The start matches already come from the type-name index.
     */
    private fun defaultScopeMiddleMatches(prefix: String): List<KotlinSymbol> {
        if (prefix.isEmpty()) return emptyList()
        val m = KotlinNameMatcher(prefix)
        return service.defaultScopeTypes { name -> m.matches(name) && !m.isStartMatch(name) }
    }

    /**
     * The RECEIVER-type candidates for a declaration-name spot: the types that can precede the name being
     * typed, turning the declaration into an extension (`fun <caret>` → `fun String.shout()`). Null (offer
     * nothing, as before) when this isn't such a spot:
     *  - a parameter / type-parameter / class / object / typealias name, none of which takes a receiver;
     *  - a declaration that ALREADY has its receiver (`fun String.na<caret>`): the user is naming it now, so
     *    only the receiver's nested classes and companion (`fun Outer.Nested.f()`) continue the type;
     *  - a LOCAL `val`/`var`, since Kotlin forbids a local extension property (a local `fun` is fine);
     *  - a prefix that starts lowercase, which by convention is a name being invented, not a type being
     *    reached for, and the popup must not start suggesting types while someone types `fun render…`
     *    (IntelliJ's automatic popup offers none either).
     * An empty prefix still offers them, so an explicitly-invoked popup at `fun ` lists the receiver options.
     */
    private fun receiverTypeCandidates(
        markerLeaf: KtElement?,
        declName: DeclNameKind,
        prefix: String,
        resolver: KotlinResolver,
        offset: Int,
    ): KotlinSymbolService.TypeNameCandidates? {
        if (declName != DeclNameKind.DECL) return null
        val decl = markerLeaf?.parent as? KtCallableDeclaration ?: return null
        if (decl !is KtNamedFunction && decl !is KtProperty) return null
        if (decl is KtProperty && decl.isLocal) return null
        decl.receiverTypeReference?.let { receiver ->
            val type = receiver.typeElement as? KtUserType ?: return null
            if (type.typeArgumentList != null) return null
            // A type parameter (`fun <F> F.f()`) has no nested classes, whatever class shares its name.
            if (type.qualifier == null && (decl.typeParameters.any { it.name == type.referencedName } ||
                    resolver.isTypeParameterInScope(type.referencedName ?: "", offset))
            ) return null
            val fqn = runCatching { service.resolveTypeName(type.text, resolver.fileContext, resolver.enclosingClassFqn(offset)) }
                .getOrNull()?.takeIf { service.isKnownType(it) } ?: return null
            val nested = service.nestedTypesOf(fqn, prefix) +
                listOfNotNull(service.companionObjectSymbol(fqn)?.takeIf { PrefixMatcher(prefix).matches(it.name) })
            return KotlinSymbolService.TypeNameCandidates(nested, capped = false)
        }
        if (prefix.isNotEmpty() && !prefix.first().isUpperCase()) return null
        val types = service.typeNameCandidates(prefix, currentFilePath = resolver.fileContext.path)
        // The local classes declared in the enclosing blocks, which no index holds.
        val matcher = PrefixMatcher(prefix)
        val local = resolver.localTypesInScope(offset).filter { (name, _) -> matcher.matches(name) }
            .map { (name, fqn) -> KotlinSymbol(name, SymbolKind.CLASS, type = service.typeByFqn(fqn), origin = SymbolOrigin(fromSource = true, file = null)) }
        return KotlinSymbolService.TypeNameCandidates(local + types.symbols + defaultScopeMiddleMatches(prefix), types.capped)
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
    private fun overrideInsertContext(leaf: KtElement?, offset: Int): Pair<String, TextRange?> {
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
        // EVERY modifier keyword already typed, not just `override`: the stub the user is accepting carries its
        // own full modifier set, so a half-typed `suspend fun lo|` must have that `suspend` replaced too, or
        // accepting yields `suspend override suspend fun load(…)`. Annotations are not modifier keywords, so
        // they stay untouched (a hand-written `@Composable` above the member survives).
        decl.modifierList?.let { list ->
            // The token set IS the collection of its types here; PSI's `TokenSet` needed `.types` to get at
            // them, and a modifier keyword needed a cast to `KtModifierKeywordToken` because it was a distinct
            // class. In the multiplatform vocabulary that name is an alias for the element type itself.
            for (kw in KtTokens.MODIFIER_KEYWORDS) {
                list.getModifier(kw)?.textRange?.startOffset?.let { offsets += it }
            }
        }
        when (decl) {
            is KtNamedFunction -> decl.funKeyword?.textRange?.startOffset?.let { offsets += it }
            is KtProperty -> decl.valOrVarKeyword?.textRange?.startOffset?.let { offsets += it }
            else -> {}
        }
        return offsets.minOrNull()
    }

    /** The rest of a `get`/`set` prefix (`getAbs` -> `abs`): the start of the name of a property whose accessor
     *  the prefix names. Null for any other prefix. */
    /** Whether [m] is the `void` one-argument Java setter of the property [name] (`setText` of `text`, `setDaemon`
     *  of `isDaemon`). */
    private fun isVoidSetterOf(m: KotlinSymbol, name: String): Boolean {
        if (m.kind != SymbolKind.METHOD || m.isExtension || m.paramTypes.size != 1 || !m.name.startsWith("set")) return false
        if ((m.type as? KotlinType)?.qualifiedName.let { it != null && it != "kotlin.Unit" }) return false
        val rest = m.name.substring(3)
        if (rest.isEmpty()) return false
        return name == "is$rest" || (name.length == rest.length && name[0] == rest[0].lowercaseChar() && name.regionMatches(1, rest, 1, rest.length - 1))
    }

    /** Whether [prefix] is the start of `get` itself, which every property's getter name begins with. */
    private fun getterHead(prefix: String): Boolean = prefix.isNotEmpty() && prefix.length <= 3 && "get".startsWith(prefix)

    private fun accessorPrefixTail(prefix: String): String? {
        if (prefix.length < 4 || !(prefix.startsWith("get") || prefix.startsWith("set")) || !prefix[3].isUpperCase()) return null
        return prefix.substring(3).replaceFirstChar { it.lowercaseChar() }
    }

    /** Whether the property [s] answers to [prefix] through its getter's name, or its setter's for a `var`
     *  (IntelliJ gives a property those lookup strings). */
    private fun accessorMatches(s: KotlinSymbol, prefix: String): Boolean {
        if (s.kind != SymbolKind.FIELD) return false
        // `g`, `ge`, `get`: the start of every getter's name.
        if (getterHead(prefix)) return true
        val tail = accessorPrefixTail(prefix) ?: return false
        // A library property's mutability is not in its symbol: the caller fetched only those a setter backs.
        if (prefix.startsWith("set") && s.origin.fromSource && !isMutableProperty(s)) return false
        return s.name.startsWith(tail) || PrefixMatcher(tail).grade(s.name).let { it != null && it != PrefixMatcher.Grade.SUBSTRING }
    }

    /** Whether the typed [prefix] reaches an override item through its `override` lookup string. */
    private fun overrideKeywordMatches(prefix: String, matcher: PrefixMatcher): Boolean =
        prefix.isEmpty() || (firstLetterCaseAgrees(prefix, "override") && matcher.matches("override"))

    /** IntelliJ's `canCompleteDeclarationWithMember`: a function name takes a function, a property name a
     *  property, and a `val` never a `var`. */
    private fun canCompleteDeclarationWith(decl: KtCallableDeclaration, m: KotlinSymbol): Boolean = when (decl) {
        is KtNamedFunction -> m.kind == SymbolKind.METHOD
        is KtProperty -> m.kind == SymbolKind.FIELD && (decl.isVar || !isMutableProperty(m))
        else -> false
    }

    /** Whether the inherited property [m] is a `var`, read off its declaration (unknown for a binary one). */
    private fun isMutableProperty(m: KotlinSymbol): Boolean {
        if (m.kind != SymbolKind.FIELD) return false
        return when (val psi = (m.declaration() as? KotlinDomNode)?.psi) {
            is KtProperty -> psi.isVar
            is KtParameter -> psi.valOrVarKeyword?.text == "var"
            else -> false
        }
    }

    /** Whether the inherited member [m] has no implementation to inherit: abstract, or an interface member
     *  without a body. */
    private fun isAbstractMember(m: KotlinSymbol): Boolean {
        if (Modifier.ABSTRACT in m.modifiers) return true
        val psi = (m.declaration() as? KotlinDomNode)?.psi as? KtCallableDeclaration ?: return false
        if (psi.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return true
        val inInterface = (psi.parent?.parent as? KtClass)?.isInterface() == true
        return inInterface && when (psi) {
            is KtNamedFunction -> !psi.hasBody()
            is KtProperty -> psi.initializer == null && psi.getter == null && psi.delegate == null
            else -> false
        }
    }

    /** True at a member-declaration spot inside a class body (not within a function body, initializer,
     *  parameter list, supertype list, or type) — where `override` members should be offered. */
    private fun isOverridePosition(leaf: KtElement?): Boolean {
        var prev: KtElement? = null
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtClassBody -> return true
                is KtBlockExpression, is KtCallExpression, is KtValueArgumentList, is KtPropertyAccessor, is KtParameterList, is KtSuperTypeList, is KtTypeReference, is KtImportDirective, is KtPackageDirective -> return false

                is KtProperty -> if (prev != null && prev === n.initializer) return false
                is KtNamedFunction -> if (prev != null && prev === n.bodyExpression) return false
                // The name of a nested class or object being declared.
                is KtClassOrObject -> if (prev != null && prev === n.nameIdentifier) return false
            }
            prev = n
            n = n.parent
        }
        return false
    }

    /** True at a name/modifier spot in a PRIMARY constructor's parameter list (not inside a parameter's type
     *  reference or default value) — where `override val`/`var` property stubs belong. */
    private fun isPrimaryCtorParamPosition(leaf: KtElement?): Boolean {
        var prev: KtElement? = null
        var n: KtElement? = leaf
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
    private fun whenAddBranchesItem(leaf: KtElement?, offset: Int, resolver: KotlinResolver): CompletionItem? {
        climbTo<KtWhenConditionWithExpression>(leaf) ?: return null // a branch-condition slot (not a branch body)
        val whenExpr = climbTo<KtWhenExpression>(leaf) ?: return null
        val subject = whenExpr.subjectExpression ?: return null
        val fqn = (resolver.inferType(subject) ?: return null).qualifiedName
        val branches = KotlinQuickFixes.missingWhenBranches(whenExpr, fqn, service, ignore = MARKER)
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
    /** Whether [offset] sits in a line or block comment. A comment is trivia to this parser, hence the
     *  trivia-aware lookup ([kdocCompletion] explains why the plain one cannot see it). */
    private fun insideComment(kt: KtFile, offset: Int): Boolean {
        val type = kt.findElementAtIncludingTrivia(offset)?.elementType
            ?: kt.findElementAtIncludingTrivia(offset - 1)?.elementType
        return type == KtTokens.EOL_COMMENT || type == KtTokens.BLOCK_COMMENT
    }

    private fun kdocCompletion(
        kt: KtFile, leaf: KtElement?, original: String, offset: Int, prefix: String, matcher: PrefixMatcher
    ): List<CompletionItem>? {
        // A doc comment is ONE token to this parser and trivia besides, so there is no KDoc element for the
        // caret to sit in and `climbTo<KDoc>` (what this asked over PSI) could never answer. The trivia-aware
        // caret query is the equivalent question, and getting it wrong is not cosmetic: without it a caret in
        // prose falls through to the ordinary name-ref path and the doc comment fills with scope symbols.
        if (kt.findElementAtIncludingTrivia(offset)?.elementType != KtTokens.DOC_COMMENT) return null
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
     *  labeled LOOPS; `return` takes the labels of the enclosing lambdas a return reaches ([returnLabels]);
     *  `this`/`super` take enclosing class and extension names + receiver-lambda labels. */
    private fun labelCompletion(leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver): List<CompletionItem>? {
        if (climbTo<KtLabelReferenceExpression>(leaf) == null) return null
        val labels = LinkedHashSet<String>()
        // The jump or reference the label belongs to, shown whole (`break@outer`) as IntelliJ lists it; only
        // the label itself is inserted, the part before the `@` being written already.
        val keyword = when {
            climbTo<KtBreakExpression>(leaf) != null -> "break"
            climbTo<KtContinueExpression>(leaf) != null -> "continue"
            climbTo<KtThisExpression>(leaf) != null -> "this"
            climbTo<KtSuperExpression>(leaf) != null -> "super"
            climbTo<KtReturnExpression>(leaf) != null -> "return"
            else -> null
        }
        when {
            climbTo<KtBreakExpression>(leaf) != null || climbTo<KtContinueExpression>(leaf) != null ->
                forEachAncestor(leaf) { n -> if (n is KtLoopExpression) (n.parent as? KtLabeledExpression)?.getLabelName()?.let { labels += it } }

            climbTo<KtThisExpression>(leaf) != null || climbTo<KtSuperExpression>(leaf) != null ->
                forEachAncestor(leaf) { n ->
                    when (n) {
                        is KtClassOrObject -> n.name?.let { labels += it }
                        is KtLambdaExpression -> lambdaImplicitLabel(n)?.let { labels += it }
                        is KtLabeledExpression -> n.getLabelName()?.let { labels += it }
                        // An extension's receiver is `this@name` in its body (`fun String.foo()`, `val String.foo`).
                        is KtNamedFunction -> if (n.receiverTypeReference != null) n.name?.let { labels += it }
                        is KtProperty -> if (n.receiverTypeReference != null) n.name?.let { labels += it }
                    }
                }

            keyword == "return" -> labels += returnLabels(leaf, resolver)

            else -> forEachAncestor(leaf) { n -> // a bare label
                when (n) {
                    is KtLambdaExpression -> lambdaImplicitLabel(n)?.let { labels += it }
                    is KtLabeledExpression -> n.getLabelName()?.let { labels += it }
                    is KtNamedFunction -> n.name?.let { labels += it } // `return@funName` returns from it
                }
            }
        }
        return labels.filter { matcher.matches(it) }
            .map { CompletionItem(if (keyword != null) "$keyword@$it" else it, it, CompletionItemKind.KEYWORD, sortPriority = -1) }
    }

    /**
     * The labels a `return@` at [leaf] can name, innermost first, as IntelliJ offers them: each enclosing lambda's
     * (its explicit label or the call it is passed to), out to the first lambda that is not inlined, past which
     * no return reaches, or to the function itself, whose return needs no label. A label that is a keyword is
     * quoted (`return@`fun``).
     */
    private fun returnLabels(leaf: KtElement?, resolver: KotlinResolver): List<String> {
        val out = ArrayList<String>()
        var n: KtElement? = leaf
        while (n != null && n !is KtNamedFunction && n !is KtClassOrObject && n !is KtPropertyAccessor) {
            if (n is KtLambdaExpression) {
                (explicitLabel(n) ?: lambdaImplicitLabel(n))?.let { out += quotedLabel(it) }
                val call = lambdaCall(n) ?: break
                if (runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).none { it.isInline }) break
            }
            n = n.parent
        }
        return out
    }

    /** [name] as a label is written: in backticks when it is a keyword or not a plain identifier. */
    private fun quotedLabel(name: String): String =
        if (name in HARD_KEYWORDS || name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' } || name[0].isDigit()) "`$name`" else name

    /**
     * The keyword items that depend on what encloses the caret, which [KotlinKeywords] (a PSI-shape table)
     * cannot see:
     *  * `this` only where there is a `this` (an enclosing class, an extension, a receiver lambda) and `super`
     *    only inside a class: at a top-level function's statement neither means anything;
     *  * the LABELED forms beside the plain ones, as IntelliJ offers them: `return@forEach` for each enclosing
     *    lambda up to the function it is in, `break@outer`/`continue@outer` for each enclosing labeled loop
     *    (not across a lambda or function), and `this@Outer` for each enclosing class, extension function and
     *    receiver lambda.
     */
    private fun refineReceiverKeywords(
        tail: MutableList<CompletionItem>,
        leaf: KtElement?,
        offset: Int,
        prefix: String,
        resolver: KotlinResolver,
    ) {
        fun has(k: String) = tail.any { it.kind == CompletionItemKind.KEYWORD && it.label == k }
        val insideClass = run {
            var n: KtElement? = leaf
            while (n != null) {
                if (n is KtClassOrObject) return@run true
                n = n.parent
            }
            false
        }
        if (has("this") && resolver.implicitReceiversAt(offset).isEmpty()) tail.removeAll { it.kind == CompletionItemKind.KEYWORD && it.label == "this" }
        if (has("super") && !insideClass) tail.removeAll { it.kind == CompletionItemKind.KEYWORD && it.label == "super" }
        // `super<List>`, `super<Comparable>` beside `super` where the class has several supertypes (not in a
        // constructor's delegation call, where `super` is the call).
        if (has("super") && climbTo<KtConstructorDelegationCall>(leaf) == null) {
            tail += KotlinSuperAndWhereCompletion.qualifiedSuperKeywords(leaf, prefix, resolver, service)
        }

        val labeled = LinkedHashSet<String>()
        if (has("return")) returnLabels(leaf, resolver).forEach { labeled += "return@$it" }
        if (has("break") || has("continue")) {
            var n: KtElement? = leaf
            while (n != null && n !is KtLambdaExpression && n !is KtNamedFunction && n !is KtClassOrObject) {
                if (n is KtLoopExpression) (n.parent as? KtLabeledExpression)?.getLabelName()?.let { label ->
                    if (has("break")) labeled += "break@$label"
                    if (has("continue")) labeled += "continue@$label"
                }
                n = n.parent
            }
        }
        // Not at a constructor's delegation call (`constructor() : this(…)`), where `this` names the call.
        if (has("this") && climbTo<KtConstructorDelegationCall>(leaf) == null) {
            var n: KtElement? = leaf
            // Past a nested class, an object or a companion, an outer class has no `this` here.
            var outerClosed = false
            // The innermost receiver is what the plain `this` already is, so its label is not offered again
            // (IntelliJ leaves it out too); a typed `this@` lists every label through [labelCompletion].
            var innermost = true
            fun receiver(label: String?) {
                if (!innermost && label != null) labeled += "this@$label"
                innermost = false
            }
            while (n != null) {
                when (n) {
                    is KtClassOrObject -> {
                        if (!(n is KtObjectDeclaration && n.isObjectLiteral()) && (!outerClosed || isSingletonScope(n))) {
                            receiver(n.name)
                        } else if (innermost && n is KtObjectDeclaration && n.isObjectLiteral()) innermost = false
                        if (closesOuterInstanceScope(n)) outerClosed = true
                    }
                    is KtNamedFunction -> if (!outerClosed && n.receiverTypeReference != null) receiver(n.name)
                    is KtLambdaExpression -> if (!outerClosed && resolver.lambdaReceiverType(n) != null) {
                        receiver(explicitLabel(n) ?: lambdaImplicitLabel(n))
                    }
                }
                n = n.parent
            }
        }
        for (k in labeled) {
            if (!k.startsWith(prefix, ignoreCase = true)) continue
            tail += CompletionItem(k, k, CompletionItemKind.KEYWORD, sortPriority = 50)
        }
    }

    /**
     * The type `this` ([label] `this`) or `this@name` stands for at [offset]: the innermost implicit receiver,
     * or the class or extension function of that name around [leaf]. Null when unknown (a lambda's label).
     */
    private fun thisTypeAt(label: String, leaf: KtElement?, offset: Int, resolver: KotlinResolver): KotlinType? {
        if (label == "this") return runCatching { resolver.implicitReceiversAt(offset).firstOrNull() }.getOrNull()
        val name = label.substringAfter('@', "").ifEmpty { return null }
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtClassOrObject -> if (n.name == name) return n.fqName?.asString()?.let { service.typeByFqn(it) }
                is KtNamedFunction -> if (n.name == name) {
                    val receiver = n.receiverTypeReference?.text ?: return null
                    return runCatching { service.typeFromText(receiver, resolver.fileContext) }.getOrNull()
                }
                // A receiver lambda's `this@label` (its own label or the call it is passed to) is that receiver.
                is KtLambdaExpression -> if ((explicitLabel(n) ?: lambdaImplicitLabel(n)) == name) {
                    return runCatching { resolver.lambdaReceiverType(n) }.getOrNull()
                }
            }
            n = n.parent
        }
        return null
    }

    /**
     * Whether a plain `return` at [leaf] may leave the enclosing function: every lambda between them is an
     * argument of an `inline` function's call (`forEach { return }`). A lambda stored in a value, passed to an
     * ordinary function or to a call that does not resolve cannot return from its caller.
     */
    private fun plainReturnAllowed(leaf: KtElement?, resolver: KotlinResolver): Boolean {
        var n: KtElement? = leaf
        var prev: KtElement? = null
        while (n != null) {
            when (n) {
                // A function parameter's default value: IntelliJ's keyword completion offers `return` there.
                is KtParameter -> if (prev != null && prev === n.defaultValue && n.parent?.parent is KtNamedFunction) return true
                // An expression body is the function's value: a `return` there needs a declared type and must
                // not be the whole body (`fun f(): Int = p ?: return 0` is fine, `fun f() = return` is not).
                is KtNamedFunction -> return n.hasBlockBody() ||
                    (n.typeReference != null && n.bodyExpression !== climbTo<KtNameReferenceExpression>(leaf))
                // So is a getter's: `get() = return` is no getter.
                is KtPropertyAccessor -> return n.hasBlockBody() || n.bodyExpression !== climbTo<KtNameReferenceExpression>(leaf)
                is KtSecondaryConstructor -> return true
                is KtClassOrObject -> return false
                is KtLambdaExpression -> {
                    val call = lambdaCall(n) ?: return false
                    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
                    if (targets.none { it.isInline }) return false
                }
            }
            prev = n
            n = n.parent
        }
        return false
    }

    /**
     * IntelliJ's `return` shortcuts for the function the caret returns from, by its declared type: `return null`
     * for a nullable one, `return true` / `return false` for a Boolean, `return emptyList()` for a List,
     * Collection or Iterable and `return emptySet()` for a Set.
     */
    private fun returnValueShortcuts(leaf: KtElement?, prefix: String): List<CompletionItem> {
        val fn = climbTo<KtNamedFunction>(leaf) ?: return emptyList()
        val type = fn.typeReference?.text?.trim() ?: return emptyList()
        val nullable = type.endsWith("?")
        val base = type.removeSuffix("?").substringBefore('<').trim().substringAfterLast('.')
        val values = buildList {
            if (nullable) add("null")
            when (base) {
                "Boolean" -> { add("false"); add("true") }
                "List", "Collection", "Iterable" -> add("emptyList()")
                "Set" -> add("emptySet()")
            }
        }
        return values.map { "return $it" }.filter { it.startsWith(prefix) }
            .map { CompletionItem(it, it, CompletionItemKind.KEYWORD, sortPriority = 50) }
    }

    /** The call a lambda is an argument of (trailing or in the parentheses), or null. */
    private fun lambdaCall(lambda: KtLambdaExpression): KtCallExpression? {
        var p = lambda.parent
        if (p is KtLabeledExpression) p = p.parent
        return when (p) {
            is KtLambdaArgument -> p.parent as? KtCallExpression
            is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        }
    }

    /** The label written on a lambda (`outer@ { … }`), or null. */
    private fun explicitLabel(lambda: KtLambdaExpression): String? =
        (lambda.parent as? KtLabeledExpression)?.getLabelName()

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

    private inline fun forEachAncestor(leaf: KtElement?, action: (KtElement) -> Unit) {
        var n: KtElement? = leaf
        while (n != null) { action(n); n = n.parent }
    }

    /** The call whose argument list contains [leaf] (for named-argument completion), or null. */
    private fun namedArgCallOf(leaf: KtElement?): KtCallExpression? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return list.parent as? KtCallExpression
    }

    /** The argument list of a constructor-DELEGATION call whose IMMEDIATE arguments contain [leaf] — a
     *  supertype call (`: Base(…)`, [KtSuperTypeCallEntry]) or a secondary-ctor delegation (`: this(…)` /
     *  `: super(…)`, [KtConstructorDelegationCall]) — or null. Keys on the nearest argument's list owner, so a
     *  nested call inside a delegation argument (`: Base(foo(<caret>))`) is correctly excluded. */
    private fun delegationArgListOf(leaf: KtElement?): KtValueArgumentList? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return if (list.parent is KtSuperTypeCallEntry || list.parent is KtConstructorDelegationCall) list else null
    }

    /** The SEALED subject type of the enclosing `when` when [leaf] sits at an `is <caret>` type pattern (the
     *  position where only the subject's subclasses are relevant), else null — not an `is` pattern, a
     *  subjectless `when`, an unresolved subject, or a subject that is not a known sealed type. */
    private fun sealedWhenSubject(leaf: KtElement?, resolver: KotlinResolver): KotlinType? {
        // `x is <caret>`, `x as <caret>`, `x as? <caret>` on a sealed `x`: its inheritors first, as IntelliJ's
        // `PreferredSubtypeWeigher` ranks them (the preferred type is the one `getPreferredSealedType` reads).
        val typeRef = climbTo<KtTypeReference>(leaf)
        val castSubject = when (val owner = typeRef?.parent) {
            is KtIsExpression -> owner.leftHandSide.takeIf { owner.typeReference === typeRef }
            is KtBinaryExpressionWithTypeRHS -> owner.left.takeIf {
                owner.right === typeRef && owner.operationReference.getReferencedNameElementType().let { op ->
                    op == KtTokens.AS_KEYWORD || op == KtTokens.AS_SAFE
                }
            }
            else -> null
        }
        if (castSubject != null) {
            val t = resolver.inferType(castSubject) ?: return null
            return if (service.sealedSubclassesOf(t.qualifiedName) != null) t.withNullable(false) else null
        }
        climbTo<KtWhenConditionIsPattern>(leaf) ?: return null
        val whenExpr = climbTo<KtWhenExpression>(leaf) ?: return null
        val subject = whenExpr.subjectExpression ?: return null
        val t = resolver.inferType(subject) ?: return null
        return if (service.sealedSubclassesOf(t.qualifiedName) != null) t else null
    }

    /** The named-argument items at the caret ([namedArgumentItems]), and whether ONLY a named argument may be
     *  written there: after a named argument out of its own position, Kotlin admits no further positional one. */
    private class NamedArgs(val items: List<CompletionItem>, val onlyNamed: Boolean)

    /** The arguments before the caret mapped onto one overload's parameters: the filled indices, the positional
     *  (index, argument) pairs, and the next positional index (-1 once only named arguments may follow). */
    private class ArgMapping(val filled: Set<Int>, val positional: List<Pair<Int, KtValueArgument>>, val next: Int)

    /**
     * The named-argument items for the argument the caret is in, the way IntelliJ offers them:
     *  * only where an argument NAME can go: the argument is a bare name being typed, or the caret is on the
     *    name of an already-named one (then the bare name is inserted, the ` = ` being there already). In the
     *    VALUE of a named argument (`foo(a = par|)`) none are offered;
     *  * per callee overload the arguments before the caret map onto (see [mapArguments]) and whose positional
     *    arguments' types fit, falling back to every overload they map onto when none type-checks;
     *  * a parameter those arguments already fill is left out (a named argument written AFTER the caret is
     *    not counted, as IntelliJ does not count it);
     *  * for the parameter this argument would fill positionally, a Boolean one also gets `p = false` and
     *    `p = true`, a nullable one `p = null`; and any parameter with a same-named value in scope whose type
     *    fits gets `p = p`.
     */
    private fun namedArgumentItems(
        call: KtCallExpression,
        markerLeaf: KtElement?,
        offset: Int,
        matcher: PrefixMatcher,
        resolver: KotlinResolver,
    ): NamedArgs {
        val none = NamedArgs(emptyList(), onlyNamed = false)
        val arg = climbTo<KtValueArgument>(markerLeaf) ?: return none
        val editingName = climbTo<KtValueArgumentName>(markerLeaf) != null
        if (!editingName) {
            if (arg.getArgumentName() != null) return none
            if (arg.getArgumentExpression() !is KtNameReferenceExpression) return none
        }
        val args = call.valueArguments
        val at = args.indexOf(arg).takeIf { it >= 0 } ?: return none
        val before = args.subList(0, at)

        fun usableNames(s: KotlinSymbol) =
            s.paramNames.all { it.isNotEmpty() && (s.origin.fromSource || !resolver.isSyntheticParamName(it)) }
        fun fitsTypes(s: KotlinSymbol, m: ArgMapping): Boolean = m.positional.all { (i, a) ->
            val want = s.paramTypes.getOrNull(i) as? KotlinType ?: return@all true
            val got = a.getArgumentExpression()?.let { resolver.inferType(it) } ?: return@all true
            argumentFits(want, got)
        }
        val mapped = (resolver.callTargets(call) + companionInvokes(call, resolver)).filterNot { resolver.isJavaCallable(it) }
            .filter(::usableNames).mapNotNull { s -> mapArguments(s, before)?.let { s to it } }
        val applicable = mapped.filter { (s, m) -> fitsTypes(s, m) }.ifEmpty { mapped }

        val out = LinkedHashMap<String, CompletionItem>()
        var smartCasts: SmartCasts? = null
        for ((s, m) in applicable) {
            s.paramNames.forEachIndexed { i, name ->
                if ((i in m.filled && i != s.varargParamIndex) || !matcher.matches(name)) return@forEachIndexed
                val type = s.paramTypes.getOrNull(i) as? KotlinType
                val p = ParamInfo(name, type)
                out.getOrPut("$name =") { KotlinCompletionItems.namedArgItem(p, bareName = editingName) }
                if (editingName) return@forEachIndexed
                if (i == m.next && type != null) {
                    val boolean = type.qualifiedName == "kotlin.Boolean"
                    val values = listOfNotNull(
                        "false".takeIf { boolean }, "null".takeIf { type.nullable }, "true".takeIf { boolean },
                    )
                    for (v in values) out.getOrPut("$name = $v") { KotlinCompletionItems.namedArgValueItem(p, v) }
                }
                // A value declared in this file (a local, a parameter, an enclosing member, a top-level one); an
                // imported one is not offered, as IntelliJ does not offer it.
                val same = (resolver.localsAt(offset, name) + resolver.sameFileScopeSymbols(offset, name)).firstOrNull {
                    it.name == name && it.kind in VALUE_KINDS
                } ?: resolver.implicitReceiverMember(name, offset)?.takeIf { it.kind in VALUE_KINDS }
                val sameType = same?.type as? KotlinType
                // A smart cast counts (`if (value is String) consume(value = value)`), as IntelliJ judges the
                // value by its smart-cast type.
                fun smartCastFits(): Boolean {
                    if (same == null || type == null || sameType == null) return false
                    val (proven, nonNull) = (smartCasts ?: SmartCasts(resolver, offset, null).also { smartCasts = it }).of(same, sameType, type)
                    return proven.any { argumentFits(type, it) } || (nonNull && sameType.nullable && argumentFits(type, sameType.withNullable(false)))
                }
                if (same != null && (type == null || sameType == null || argumentFits(type, sameType) || smartCastFits())) {
                    out.getOrPut("$name = $name") { KotlinCompletionItems.namedArgValueItem(p, name) }
                }
            }
        }
        val onlyNamed = !editingName && applicable.isNotEmpty() && applicable.all { (_, m) -> m.next < 0 }
        return NamedArgs(out.values.toList(), onlyNamed)
    }

    /** The companion's `operator fun invoke` of the class a call names (`Foo(…)` with `companion object { operator
     *  fun invoke(…) }`), which the call may resolve to beside the constructors. */
    private fun companionInvokes(call: KtCallExpression, resolver: KotlinResolver): List<KotlinSymbol> {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return emptyList()
        if (call.parent is KtQualifiedExpression || callee.getReferencedName().firstOrNull()?.isUpperCase() != true) return emptyList()
        val fqn = runCatching { resolver.typeDenotationFqn(callee) }.getOrNull() ?: return emptyList()
        return service.companionMembersFor(fqn, "invoke").filter { it.name == "invoke" && it.kind == SymbolKind.METHOD && isOperatorFunction(it) }
    }

    /**
     * IntelliJ's `K2MultipleArgumentContributor`: at the last argument of a call or an indexing (`foo(<caret>)`,
     * `foo[a, <caret>]`) with no named argument yet, one item per signature that fills two or more of the
     * parameters still to pass, left to right, each with a value of the same name declared in this file (a local,
     * a parameter, an enclosing class's member, a top-level one; never an imported one) whose type, smart casts
     * included, the parameter accepts. Only the longest run per signature is offered; of two signatures giving
     * the same run, the one it completes wins.
     */
    private fun multipleArgumentItems(markerLeaf: KtElement?, offset: Int, resolver: KotlinResolver): List<CompletionItem> {
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf) ?: return emptyList()
        // The parameters [s] still wants after the first [passed], in order; null when a binary one's names were
        // stripped (`p0`), which no value can be matched by.
        // A Java parameter's type is a platform type: the Kotlin type it maps to, nullable or not.
        fun remaining(s: KotlinSymbol, passed: Int): List<Pair<String, KotlinType?>>? {
            val java = !s.origin.fromSource && resolver.isJavaCallable(s)
            return s.paramNames.indices.drop(passed).map { i ->
                val n = s.paramNames[i]
                if (n.isEmpty() || (!s.origin.fromSource && resolver.isSyntheticParamName(n))) return null
                val t = s.paramTypes.getOrNull(i) as? KotlinType
                n to if (java && t != null && !t.isTypeParameter) {
                    // A Java collection is the read-only Kotlin one, which the mutable one also is.
                    val q = t.qualifiedName
                    KotlinType(Builtins.kotlinTypeFor(q) ?: JAVA_COLLECTIONS[q] ?: q, nullable = true)
                } else t
            }
        }
        val signatures: List<List<Pair<String, KotlinType?>>>
        // A supertype call in a class header reads the primary constructor's parameters.
        var header: KtClass? = null
        val arg = nameRef.parent as? KtValueArgument
        if (arg != null) {
            if (arg.getArgumentName() != null) return emptyList()
            val list = arg.parent as? KtValueArgumentList ?: return emptyList()
            val args = list.arguments
            if (args.lastOrNull() !== arg || args.any { it.getArgumentName() != null }) return emptyList()
            val passed = args.size - 1
            signatures = when (val owner = list.parent) {
                is KtCallExpression -> resolver.callTargets(owner).filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
                    .mapNotNull { remaining(it, passed) }
                is KtSuperTypeCallEntry, is KtConstructorDelegationCall -> {
                    if (owner is KtSuperTypeCallEntry) header = climbTo<KtClass>(owner)
                    listOf(resolver.delegationCallParameters(markerLeaf).drop(passed).map { it.name to it.type })
                }
                else -> return emptyList()
            }
        } else {
            val access = (nameRef.parent?.parent as? KtArrayAccessExpression) ?: return emptyList()
            val indices = access.indexExpressions
            if (indices.lastOrNull() !== nameRef) return emptyList()
            val recv = access.arrayExpression?.let { resolver.inferType(it) } ?: return emptyList()
            signatures = service.membersNamed(recv.qualifiedName, recv.typeArguments, "get")
                .filter { it.kind == SymbolKind.METHOD && it.name == "get" }
                .mapNotNull { remaining(it, indices.size - 1) }
        }
        val usable = signatures.filter { it.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        // The value each name reads at the caret, the innermost declaration first, asked once per name.
        val smartCasts = SmartCasts(resolver, offset, null)
        val values = HashMap<String, KotlinSymbol?>()
        fun valueNamed(name: String): KotlinSymbol? = values.getOrPut(name) {
            header?.primaryConstructorParameters?.firstOrNull { it.name == name }?.let { p ->
                return@getOrPut KotlinSymbol(name, SymbolKind.PARAMETER, p.typeReference?.text?.let { t ->
                    runCatching { service.typeFromText(t, resolver.fileContext) }.getOrNull()
                })
            }
            (resolver.localsAt(offset, name) + resolver.sameFileScopeSymbols(offset, name)).firstOrNull { it.name == name && it.kind in VALUE_KINDS }
                ?: resolver.implicitReceiverMember(name, offset)?.takeIf { it.kind in VALUE_KINDS }
        }
        fun accepts(want: KotlinType?, s: KotlinSymbol): Boolean {
            val got = s.type as? KotlinType ?: return true
            if (want == null || argumentFits(want, got)) return true
            val (proven, nonNull) = smartCasts.of(s, got, want)
            return proven.any { argumentFits(want, it) } || (nonNull && got.nullable && argumentFits(want, got.withNullable(false)))
        }
        val matched = LinkedHashMap<List<String>, Boolean>()
        for (sig in usable) {
            val names = ArrayList<String>()
            for ((name, type) in sig) {
                val v = valueNamed(name) ?: break
                if (!accepts(type, v)) break
                names += name
            }
            if (names.size < 2) continue
            val complete = names.size == sig.size
            matched[names] = matched[names] == true || complete
        }
        return matched.map { (names, complete) ->
            val text = names.joinToString(", ")
            CompletionItem(
                label = text,
                insertText = if (complete) text else "$text, ",
                kind = CompletionItemKind.VARIABLE,
                sortPriority = -1,
                // `createMultipleArgumentsLookupElement` marks them MATCHES_PREFERRED: they fill the slots exactly.
                relevance = CompletionRelevance(fitsExpectedType = true),
            )
        }
    }

    /**
     * [before] mapped onto [s]'s parameters the way the compiler maps them: a positional argument fills the
     * next parameter, a named one in its own position does too, and a named one out of position ends the
     * positional run, after which only named arguments may follow. Null when they do not map onto [s].
     */
    private fun mapArguments(s: KotlinSymbol, before: List<KtValueArgument>): ArgMapping? {
        val names = s.paramNames
        val filled = HashSet<Int>()
        val positional = ArrayList<Pair<Int, KtValueArgument>>()
        var next = 0
        for (a in before) {
            val named = a.getArgumentName()?.asName?.identifier
            if (named != null) {
                val i = names.indexOf(named).takeIf { it >= 0 } ?: return null
                filled += i
                next = if (next >= 0 && i == next) next + 1 else -1
            } else {
                if (next < 0) return null
                val i = if (s.varargParamIndex in 0 until next) s.varargParamIndex else next
                if (i >= names.size) return null
                filled += i
                positional += i to a
                if (i != s.varargParamIndex) next++
            }
        }
        return ArgMapping(filled, positional, next)
    }

    /** Whether a value of [got] can be passed for a parameter of type [want], erring towards yes: a type
     *  parameter accepts anything, and an integer literal's type is fixed by the parameter it is passed to. */
    private fun argumentFits(want: KotlinType, got: KotlinType): Boolean {
        if (want.isTypeParameter || got.isTypeParameter) return true
        if (got.nullable && !want.nullable && got.qualifiedName != "kotlin.Nothing") return false
        if (want.qualifiedName in NUMERIC_TYPES && got.qualifiedName in NUMERIC_TYPES) return true
        return runCatching { want.isAssignableFrom(got) }.getOrDefault(true)
    }

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
        leaf: KtElement?, matcher: PrefixMatcher, resolver: KotlinResolver
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

    /** Whether the companion member [m] is a value of [expected]: a property of exactly that type, or a factory
     *  function (not an extension) returning it or a subtype. */
    /**
     * What `K2TypeInstantiationContributor` offers for the type a value slot expects, beside the class itself and
     * its constructor call: an anonymous object of it where it can be inherited (`object : Comparator<String>{...}`,
     * an interface, or an open or abstract class with a constructor that is not private), and for a SAM interface
     * (a Java one, or a `fun interface`) its SAM constructor (`Runnable {...}`). Both fit the expected type. Only
     * the expected type's own declaration is read; nothing is looked up by name.
     */
    private fun instantiationItems(expected: KotlinType, matcher: PrefixMatcher, autoImport: KotlinAutoImport, out: MutableList<CompletionItem>) {
        val fqn = expected.qualifiedName
        if (expected.isTypeParameter || fqn in NOT_CONSTRUCTED || fqn == "kotlin.Any" || fqn == "java.lang.Object" ||
            TypeRendering.isFunctionType(fqn)
        ) return
        val simple = fqn.substringAfterLast('.')
        val objectFits = matcher.prefix.isEmpty() || "object".startsWith(matcher.prefix)
        val samFits = matcher.matches(simple)
        if (!objectFits && !samFits) return
        val iface = service.isInterfaceType(fqn) ?: return
        val cls = (runCatching { service.classDeclaration(fqn) }.getOrNull() as? KotlinDomNode)?.psi as? KtClass
        val inheritable = when {
            cls != null -> !cls.isEnum() && !cls.isSealed() && !cls.isAnnotation() && !cls.isData() &&
                (iface || cls.hasModifier(KtTokens.OPEN_KEYWORD) || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD)) &&
                (iface || openConstructor(cls))
            iface -> true
            else -> service.isFinalType(fqn) == false && service.enumConstantsOf(fqn).isEmpty() &&
                service.constructorsOf(fqn).any { Modifier.PRIVATE !in it.modifiers }
        }
        val shown = expected.withNullable(false).toString()
        val fits = CompletionRelevance(fitsExpectedType = true, proximity = ANONYMOUS_OBJECT_PROXIMITY)
        val import = autoImport.editForType(fqn)
        if (inheritable && objectFits) {
            val head = if (iface) "object : $shown" else "object : $shown()"
            out += CompletionItem(
                label = "$head{...}",
                insertText = "$head {\n}",
                kind = CompletionItemKind.KEYWORD,
                detail = shown,
                sortPriority = 0,
                additionalEdits = import,
                caret = CaretAction.At(head.length + 2),
                relevance = fits,
            )
        }
        // A SAM constructor: a Java interface with one abstract method, or a Kotlin `fun interface`.
        val sam = iface && samFits && (if (cls != null) cls.hasModifier(KtTokens.FUN_KEYWORD) else service.isJavaType(fqn)) &&
            service.functionalShape(expected) != null
        if (sam) {
            out += CompletionItem(
                label = "$simple {...}",
                insertText = "$simple {  }",
                kind = CompletionItemKind.INTERFACE,
                detail = shown,
                sortPriority = 0,
                additionalEdits = import,
                caret = CaretAction.At(simple.length + 3),
                relevance = CompletionRelevance(fitsExpectedType = true, proximity = PROXIMITY_CLASSIFIER),
            )
        }
    }

    /** Whether [cls] has a constructor an anonymous subclass may call: one that is not private. */
    private fun openConstructor(cls: KtClass): Boolean {
        val primary = cls.primaryConstructor
        val secondary = cls.secondaryConstructors
        if (primary == null && secondary.isEmpty()) return true
        return (primary != null && !primary.hasModifier(KtTokens.PRIVATE_KEYWORD)) ||
            secondary.any { !it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
    }

    private fun companionValueOf(m: KotlinSymbol, expected: KotlinType): Boolean {
        val t = m.type as? KotlinType ?: return false
        if (t.nullable && !expected.nullable) return false
        return when {
            m.kind == SymbolKind.FIELD -> t.qualifiedName == expected.qualifiedName
            m.kind == SymbolKind.METHOD && !m.isExtension ->
                t.qualifiedName == expected.qualifiedName || runCatching { expected.isAssignableFrom(t) }.getOrDefault(false)
            else -> false
        }
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
        instantiationItems(expected, matcher, autoImport, out)
        if (expected.qualifiedName == "kotlin.Boolean") {
            for (b in listOf("true", "false")) {
                if (matcher.matches(b)) {
                    // A literal is a keyword: it fits, but ranks after the fitting values in scope, as IntelliJ's
                    // kind weigher puts keywords after callables.
                    out += CompletionItem(
                        b, b, CompletionItemKind.KEYWORD, sortPriority = 0,
                        relevance = fits.copy(proximity = KotlinCompletionWeighing.KEYWORD_PROXIMITY),
                    )
                }
            }
        }
        // A primitive's or `String`'s own constants (`Int.MAX_VALUE`, `Char.MIN_VALUE`) are no value anyone means
        // by such a slot, and IntelliJ does not offer them there.
        val primitive = expected.qualifiedName in NOT_CONSTRUCTED
        val consts = if (primitive) emptyList() else service.enumConstantsOf(expected.qualifiedName)
        if (consts.isNotEmpty()) {
            consts.filter { matcher.matches(it.name) }
                .forEach { shortEnumItem(it); constItem(it, CompletionItemKind.ENUM_CONSTANT) }
        } else if (!primitive) {
            // Not an enum — offer the type's companion constants OF that type (`val Transparent: Color` on
            // `Color`'s companion), the value-class / object-constant idiom. Capped so a large palette (Color
            // has dozens) doesn't flood the popup; prefix-filtering narrows it as the user types.
            // The companion's factories returning the type too (`fun make(): Type`), inserted as a call.
            service.companionMembersFor(expected.qualifiedName, matcher.prefix)
                .filter { companionValueOf(it, expected) }
                .take(MAX_EXPECTED_CONSTANTS).forEach {
                    if (it.kind == SymbolKind.FIELD) constItem(it, CompletionItemKind.FIELD)
                    else {
                        val call = "$simple.${it.name}"
                        out += CompletionItem(
                            label = "$call(${it.paramNames.joinToString(", ")})",
                            insertText = "$call()",
                            kind = CompletionItemKind.METHOD,
                            detail = simple,
                            sortPriority = -1,
                            symbol = it,
                            additionalEdits = autoImport.editForType(expected.qualifiedName),
                            relevance = fits,
                            caret = if (it.paramTypes.isEmpty()) CaretAction.AtEnd else CaretAction.At(call.length + 1),
                        )
                    }
                }
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
        val popularity: Int,
        val distance: Int,
        /** The name-length tiebreak, or 0 on member access, whose members sort alphabetically instead. */
        val lengthKey: Int,
        /** For a local or a parameter, its scope, innermost first; for a top-level callable, the scope that
         *  brings it in (see [importScopeOf]); 0 otherwise. */
        val scopeOrder: Int = 0,
        /** 1 for a function in a bare name position, which IntelliJ lists after the variables of its rank. */
        val functionKey: Int = 0,
        /** For a candidate that needs an import, IntelliJ's not-imported tier ([KotlinAutoImport.notImportedTier]). */
        val importTier: Int = 0,
        /** Deprecated where it is used (see [deprecatedForRanking]). */
        val deprecated: Boolean = false,
        /** 0 for a member the function being written overrides too (see [contextualOverrides]), else 1. */
        val contextual: Int = 1,
        /** For a bare name read through an implicit receiver, that receiver's index, innermost first (see
         *  [ImplicitReceiverLevels]); 0 otherwise. */
        val receiverLevel: Int = 0,
        /** Reached by its simple name although accepting it imports it: a class nested in the sealed type the
         *  context expects, whose static scope IntelliJ's classifier contributor searches too. */
        reachable: Boolean = false,
    ) {
        /** No auto-import needed (already visible) — the in-scope-before-needs-import rank key, computed once. */
        val inScope: Boolean = reachable || importEdit.isEmpty()

        fun relevance() = CompletionRelevance(
            fitsExpectedType = fitsExpected,
            contextBoost = boosted,
            callableWeight = group,
            inScope = inScope,
            deprecated = deprecated,
            proximity = proximity,
            popularity = popularity,
            receiverDistance = distance,
        )
    }

    /**
     * Ranking proximity for [s], IntelliJ's `kotlin.kind` order on one scale (lower is nearer): locals and
     * parameters, then members, then top-level callables and the other kinds, then the keywords
     * ([KEYWORD_PROXIMITY], placed by [KotlinCompletionWeighing]), then classifiers.
     *
     * One exception in a bare [nameReference] position: a capitalized top-level LIBRARY callable is a factory
     * named after a type (`String(stringBuilder: StringBuilder)`, `Text(...)`), no nearer than the type it is
     * named after, so it sinks below the classifiers. Without this the stdlib `String(...)` factories sorted
     * above the `String`/`StringBuilder` CLASSES, burying the type the user is spelling out. Lowercase
     * top-level callables (`println`, a project's `fun helper()`) stay with the callables, above the keywords
     * and the classifiers, as IntelliJ ranks them. Kicks in only after grade ties, so a lowercase prefix
     * (`printl` -> `println`) still wins on its better match grade.
     *
     * An `object` that fits the expected type in an expression position ranks as the value it is
     * ([objectAsValue]), with the callables, ahead of the classes that merely fit too (IntelliJ weighs objects
     * as callables there).
     */
    private fun proximityOf(s: KotlinSymbol, nameReference: Boolean, objectAsValue: Boolean = false, local: Boolean = false): Int {
        val topLevelCallable = isTopLevelSymbol(s)
        return when {
            local || s.kind == SymbolKind.LOCAL_VARIABLE || s.kind == SymbolKind.PARAMETER -> PROXIMITY_LOCAL
            // A package beside the names of code (`kotlin.` in a type or an expression) comes after all of them.
            s.kind == SymbolKind.PACKAGE -> PROXIMITY_PACKAGE
            s.kind in TYPE_KINDS -> if (objectAsValue) PROXIMITY_TOP_LEVEL else PROXIMITY_CLASSIFIER
            topLevelCallable && nameReference && !s.origin.fromSource && s.name.firstOrNull()?.isUpperCase() == true ->
                TOP_LEVEL_CALLABLE_PROXIMITY
            topLevelCallable -> PROXIMITY_TOP_LEVEL
            s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD -> PROXIMITY_MEMBER
            else -> PROXIMITY_OTHER
        }
    }

    /**
     * Whether the project class [s] names a value of the [expected] type through its companion object: a class
     * written as a value IS its companion (`action(MyClass)` where `MyClass.Companion : MyInterface`), so it
     * fits that slot as IntelliJ judges it. Project classes only: their shapes are in the source model, so the
     * check stays a map lookup however many types the prefix matches.
     */
    private fun companionFits(s: KotlinSymbol, expected: KotlinType): Boolean {
        if (s.kind !in TYPE_KINDS || !s.origin.fromSource) return false
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return false
        val companion = runCatching { service.companionObjectSymbol(fqn) }.getOrNull() ?: return false
        val type = companion.type as? KotlinType ?: return false
        return runCatching { expected.isAssignableFrom(type) }.getOrDefault(false)
    }

    /**
     * IntelliJ's expected-type verdict for [s] (its `kotlin.expected.type` weigher): [FITS]; [FITS_NON_NULL],
     * its type fits once the `?` is dropped; [UNTYPED], nothing to judge (a classifier, an untyped symbol); or
     * [MISMATCH], a typed value that does not fit, which IntelliJ ranks below even the untyped ones. A local
     * read under a smart cast is judged by the type it has there (`if (x is String) return x`).
     */
    private fun expectedVerdict(
        s: KotlinSymbol, expected: KotlinType, nameReference: Boolean, resolver: KotlinResolver, offset: Int,
        smartCast: SmartCasts? = null,
    ): Int {
        if (matchesExpected(s, expected) || (nameReference && companionFits(s, expected))) return FITS
        if (s.kind in TYPE_KINDS) return UNTYPED
        // Where a Unit is wanted any call does (its value is discarded), as IntelliJ judges it.
        if (expected.qualifiedName == "kotlin.Unit" && !expected.nullable && s.kind == SymbolKind.METHOD) return FITS
        val narrowed = if ((s.kind == SymbolKind.LOCAL_VARIABLE || s.kind == SymbolKind.PARAMETER) && !reassignedBefore(s, offset, resolver)) {
            runCatching { resolver.smartCastTypeAt(s.name, offset) }.getOrNull()
        } else null
        if (narrowed != null && typeFits(narrowed, expected)) return FITS
        // IntelliJ's `ExpectedTypeWeigher` judges a value by its smart-cast type at the caret: a null check, an
        // intersection of `is` checks, a checked member path (`box.value is String`), a context parameter.
        if (smartCast != null) {
            val declared = s.type as? KotlinType
            val (proven, nonNull) = smartCast.of(s, declared, expected)
            if (proven.any { typeFits(it, expected) }) return FITS
            if (nonNull && declared != null && declared.nullable && typeFits(declared.withNullable(false), expected)) return FITS
        }
        val t = narrowed ?: (s.type as? KotlinType) ?: return UNTYPED
        if (expected.isTypeParameter) return UNTYPED
        if (t.nullable && !expected.nullable && typeFits(t.withNullable(false), expected)) return FITS_NON_NULL
        return MISMATCH
    }

    /**
     * The smart casts in effect at the caret for the values a completion offers, asked lazily and only of a value
     * whose declared type misses the expected one (see [expectedVerdict]). [receiverPath] is the access path of an
     * explicit receiver (`box.` in `box.value<caret>`), null for a bare name. The file's `is`/`as` targets are read
     * once, on the first question.
     */
    private inner class SmartCasts(private val resolver: KotlinResolver, private val offset: Int, private val receiverPath: String?) {
        private val targets by lazy { runCatching { resolver.completionCastTargets() }.getOrDefault(emptyMap()) }

        fun of(s: KotlinSymbol, declared: KotlinType?, expected: KotlinType): Pair<List<KotlinType>, Boolean> {
            if (s.kind != SymbolKind.LOCAL_VARIABLE && s.kind != SymbolKind.PARAMETER && s.kind != SymbolKind.FIELD) return NO_SMART_CAST
            if (declared == null || declared.isTypeParameter) return NO_SMART_CAST
            val path = if (receiverPath != null) receiverPath + "." + s.name else s.name
            // Only a question whose answer can change the verdict is asked: a checked path, or a nullable value
            // whose non-null type would fit.
            val checked = targets[path].orEmpty().isNotEmpty()
            val nullMatters = declared.nullable && typeFits(declared.withNullable(false), expected)
            if (!checked && !nullMatters) return NO_SMART_CAST
            return runCatching { resolver.completionSmartCast(path, offset, if (checked) targets else emptyMap()) }.getOrDefault(NO_SMART_CAST)
        }
    }

    private val NO_SMART_CAST: Pair<List<KotlinType>, Boolean> = emptyList<KotlinType>() to false

    /**
     * Whether the local `var` [s] is assigned anywhere between its declaration and [offset]: an assignment
     * after an `is` check ends the smart cast, which the resolver's narrowing does not see, so the narrowed
     * type is not trusted then. A textual scan of that stretch, conservative: an assignment before the check
     * also drops the narrowing, which only ranks the local as its declared type.
     */
    private fun reassignedBefore(s: KotlinSymbol, offset: Int, resolver: KotlinResolver): Boolean {
        val decl = (s.declaration() as? KotlinDomNode)?.psi as? KtProperty ?: return false
        if (!decl.isVar) return false
        val text = resolver.ktFile.text
        val from = decl.textRange.endOffset.coerceIn(0, text.length)
        val to = offset.coerceIn(from, text.length)
        return Regex("""(?<![\w.])""" + Regex.escape(s.name) + """\s*=(?!=)""").containsMatchIn(text.subSequence(from, to))
    }

    /** Where a verdict puts a candidate that does not fit: the near-fits first, the mismatches last. */
    private fun expectedBand(verdict: Int): Int = when (verdict) {
        FITS, FITS_NON_NULL -> 0
        MISMATCH -> 2
        else -> 1
    }

    /**
     * A local's scope, innermost first, so `br5` declared in the loop body ranks over the parameter `br4`: the
     * start of the block (or parameter list) declaring it, negated, since an inner scope always starts after
     * the scopes around it. Two locals of one scope tie, and the name decides, as in IntelliJ. A symbol without
     * its declaration falls back to the scope walk's own order.
     */
    private fun localScopeOrder(s: KotlinSymbol, index: Int): Int {
        val scopeStart = s.declaration()?.parent?.range?.start ?: return Int.MIN_VALUE / 2 + index
        return -scopeStart
    }

    /** A top-level function or property: it carries a package, or, from a default-package project file, no
     *  owner at all. */
    private fun isTopLevelSymbol(s: KotlinSymbol): Boolean =
        (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) &&
            (s.packageName != null || (s.origin.fromSource && s.owner == null && s.declaringClassFqn == null))

    /** The scope that brings the top-level callable [s] in: an explicit import, the file's own package, a star
     *  import, the default imports, in IntelliJ's order (the first nearest); not imported at all last. */
    private fun importScopeOf(s: KotlinSymbol, autoImport: KotlinAutoImport): Int =
        autoImport.scopeOf(s.packageName.orEmpty(), s.name, s.origin.fromSource)

    /** Whether [leaf] is in the type of a `catch` clause's parameter. */
    private fun inCatchParameterType(leaf: KtElement?): Boolean {
        val param = climbTo<KtTypeReference>(leaf)?.parent as? KtParameter ?: return false
        return param.parent?.parent is KtCatchClause
    }

    /** Whether the classifier [s] is an `object` (a companion too): a value by its own name. */
    /** A project class nested in another by its name relative to its package (`SEALED.AAAA`), null for a top-level
     *  one or a library class. */
    private fun nestedClassName(s: KotlinSymbol): String? {
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return null
        val pkg = service.sourceClass(fqn)?.ctx?.packageName ?: return null
        val relative = if (pkg.isEmpty()) fqn else fqn.removePrefix("$pkg.")
        return relative.takeIf { '.' in it && it != fqn.substringAfterLast('.') }
    }

    /**
     * Whether a `::` reference can name [s], IntelliJ's `K2CallableReferenceCompletionContributor.filter`: not a
     * member that is an extension at the same time (it would need two receivers), not a parameter, a local
     * variable or a backing `field`, and not an enum entry.
     */
    private fun referenceableByCallableReference(s: KotlinSymbol): Boolean {
        when (s.kind) {
            SymbolKind.PARAMETER, SymbolKind.LOCAL_VARIABLE, SymbolKind.ENUM_CONSTANT -> return false
            else -> {}
        }
        val psi = (s.declaration() as? KotlinDomNode)?.psi ?: return true
        if (s.isExtension && psi.parent is KtClassBody) return false
        // A setter's or getter's `field` stands for the property it backs.
        if (s.name == "field" && psi is KtProperty && psi.name != "field") return false
        return true
    }

    /** Whether a sealed [fqn] has an abstract inheritor (an abstract class or a plain interface), through its sealed
     *  inheritors too, as `K2WhenWithSubjectConditionContributor.getAllSealedInheritors` walks them. */
    private fun hasAbstractSealedInheritor(fqn: String, seen: MutableSet<String>): Boolean {
        if (!seen.add(fqn)) return false
        for (sub in service.sealedSubclassesOf(fqn).orEmpty()) {
            val rc = service.sourceClass(sub) ?: continue
            if (rc.isSealed) {
                if (hasAbstractSealedInheritor(sub, seen)) return true
            } else if (rc.isAbstract || rc.isInterface) return true
        }
        return false
    }

    /** A project type alias [s] as the classifier it expands to (through a chain of aliases), or null when [s]
     *  is no alias: one map lookup for any other classifier. */
    private fun aliasExpanded(s: KotlinSymbol): KotlinSymbol? {
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return null
        if (!service.isProjectTypeAliasFqn(fqn)) return null
        val target = service.typeAliasChainTargetFqn(s.name, null) ?: return null
        return KotlinSymbol(s.name, s.kind, service.typeByFqn(target), origin = s.origin)
    }

    private fun isObjectValue(s: KotlinSymbol): Boolean {
        if (s.kind !in TYPE_KINDS) return false
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return false
        return runCatching { service.isSingletonObject(fqn) }.getOrDefault(false)
    }

    /** A key under which overloads that take ONLY function arguments of the same arities collide: their name,
     *  parameter names and each function parameter's arity. Null for anything else. */
    private fun lambdaShapeKey(s: KotlinSymbol): String? {
        if (s.kind != SymbolKind.METHOD || s.paramTypes.isEmpty()) return null
        val arities = s.paramTypes.map { p ->
            val t = p as? KotlinType ?: return null
            if (!TypeRendering.isFunctionType(t.qualifiedName)) return null
            t.typeArguments.size
        }
        return s.name + "/" + s.paramNames.joinToString(",") + "/" + arities.joinToString(",") + "/" + (s.receiverTypeFqn ?: "")
    }

    /**
     * The one overload to show for each group of overloads a caller cannot tell apart while typing: same name,
     * same parameters, every parameter a lambda. `sumOf { }` is written identically whether the lambda returns
     * an `Int`, a `Long` or a `BigDecimal`, and seven rows of it bury `sum`. The `Int` flavour is kept when
     * there is one, since that is what the lambda most often returns; otherwise the first.
     */
    private fun lambdaOnlyOverloads(raw: List<KotlinSymbol>): BooleanArray {
        // By position in [raw], so the caller's loop reads one flag per candidate instead of building each
        // candidate's shape key a second time.
        val groups = HashMap<String, MutableList<Int>>()
        for ((i, s) in raw.withIndex()) lambdaShapeKey(s)?.let { groups.getOrPut(it) { ArrayList(2) }.add(i) }
        val dropped = BooleanArray(raw.size)
        for (group in groups.values) {
            if (group.size < 2) continue
            val kept = group.firstOrNull { i ->
                ((raw[i].paramTypes.last() as? KotlinType)?.typeArguments?.lastOrNull())?.qualifiedName == "kotlin.Int"
            } ?: group.first()
            for (i in group) if (i != kept) dropped[i] = true
        }
        return dropped
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
    /**
     * The callable group of [s] on an explicit receiver whose type and supertypes are [chain], ported from IntelliJ's
     * `CallableWeigher` and the kinds its `CallableMetadataProvider` assigns, lowest first:
     *  0 a member the receiver's own class declares (THIS_CLASS_MEMBER);
     *  1 a member of a supertype, or an override (BASE_CLASS_MEMBER: "override members are considered as
     *    non-immediate in completion"), `equals`/`hashCode`/`toString` among them;
     *  2..5 an extension on the receiver's own type (THIS_TYPE_EXTENSION), then on a supertype, `Any` included
     *    (BASE_TYPE_EXTENSION): the project's before the libraries', as before, so a page cut at the item cap
     *    keeps them (IntelliJ prefers them later, by its proximity weigher, and has no cap);
     *  6 an extension on a bare type parameter, `fun <T> T.also()` (TYPE_PARAMETER_EXTENSION).
     */
    private fun receiverCallableGroup(s: KotlinSymbol, chain: List<String>): Int {
        if (!s.isExtension) {
            // A project member may know its class by simple name only.
            val owner = s.declaringClassFqn?.replace('$', '.') ?: ((s.owner as? KotlinSymbol)?.type as? KotlinType)?.qualifiedName
                ?: s.owner?.name
            fun names(fqn: String) = owner == fqn || ('.' !in owner.orEmpty() && fqn.substringAfterLast('.') == owner)
            return when {
                s.name in OBJECT_METHODS -> 1
                owner != null && !names(chain[0]) && chain.any(::names) -> 1
                // A project member the receiver's class declares as an `override`, read from its declaration.
                s.origin.fromSource && isOverride(s) -> 1
                else -> 0
            }
        }
        if (s.receiverTypeParam != null) return 6
        val exact = s.receiverTypeFqn == chain[0]
        return (if (s.origin.fromSource) 2 else 4) + (if (exact) 0 else 1)
    }

    /** Whether the source declaration of [s] is an `override`. */
    private fun isOverride(s: KotlinSymbol): Boolean {
        val decl = (runCatching { s.declaration() }.getOrNull() as? KotlinDomNode)?.psi as? KtDeclaration ?: return false
        return decl.hasModifier(KtTokens.OVERRIDE_KEYWORD)
    }

    private fun memberGroup(s: KotlinSymbol): Int = when {
        s.name in OBJECT_METHODS -> 4
        !s.isExtension -> 0
        s.receiverTypeParam != null || s.receiverTypeFqn == "kotlin.Any" -> 3 // universal scope function (T./Any.)
        s.origin.fromSource -> 1                                              // project-source extension
        else -> 2                                                             // library extension on a specific receiver
    }

    /** [type]'s FQN, then every supertype's, breadth-first so the nearest come first. */
    private fun supertypeChain(type: KotlinType): List<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<TypeRef>().apply { add(type) }
        while (queue.isNotEmpty() && out.size < MAX_CHAIN) {
            val t = queue.removeFirst()
            if (out.add(t.qualifiedName)) queue.addAll(t.supertypes())
        }
        // An enum class extends `Enum` without saying so, and a project enum's supertype list is what its
        // source says. Without it `name` and `ordinal` have no popularity and sort under `clone()`.
        if ("kotlin.Enum" !in out && runCatching { service.enumConstantsOf(type.qualifiedName).isNotEmpty() }.getOrDefault(false)) {
            out += "kotlin.Enum"
        }
        return out.toList()
    }

    /** How far up [chain] the member [s] is declared (0 = the receiver's own class); 0 when unknown. */
    private fun distanceIn(chain: List<String>, s: KotlinSymbol): Int {
        val owner = s.declaringClassFqn?.replace('$', '.')
        // `equals`/`hashCode`/`toString` are `Any`'s, the root of every chain, whatever class re-declares them.
        if (s.name in OBJECT_METHODS && (owner == null || owner == "kotlin.Any" || owner == "java.lang.Object")) return chain.size
        if (owner == null) return 0
        return chain.indexOf(owner).coerceAtLeast(0)
    }

    /**
     * Drops a Java getter or setter that a Kotlin property already stands for, the way IntelliJ does: `a.` on a
     * `TextView` offers `text`, not `text` and `getText()` and `setText()`. Kept when the user is spelling the
     * accessor out (a `get`/`set`/`is` prefix), and a setter overload the property can't express (`setText(Int)`)
     * always stays. Only Java members have such properties, and only on member access.
     */
    /** [scope] without the Java getters an implicit receiver's property stands for (`absolutePath` inside a
     *  `File` subclass hides `getAbsolutePath()`), as on an explicit receiver. A scope holding no member getter
     *  is returned as it is, before anything is allocated. */
    private fun withoutHiddenAccessors(scope: List<KotlinSymbol>, prefix: String): List<KotlinSymbol> {
        if (scope.none { it.kind == SymbolKind.METHOD && it.declaringClassFqn != null && !it.isExtension && it.paramTypes.isEmpty() &&
                (it.name.startsWith("get") || it.name.startsWith("is")) }
        ) return scope
        val properties = gettableProperties(scope)
        return if (properties.isEmpty()) scope else hideAccessorDuplicates(scope, prefix, properties)
    }

    private fun hideAccessorDuplicates(
        raw: List<KotlinSymbol>,
        prefix: String,
        properties: Map<String, KotlinSymbol>,
    ): List<KotlinSymbol> {
        val setterOnly = setterOnlyProperties(raw, properties)
        val kept = if (setterOnly.isEmpty()) raw else raw.filterNot { it in setterOnly }
        if (properties.isEmpty()) return kept
        // The getters IntelliJ keeps as calls (`NotPropertiesService`: `AtomicInteger.getAndIncrement`,
        // `Socket.getInputStream`), whose read-only property it leaves out instead.
        var notProperties: HashSet<String>? = null
        for (s in kept) {
            if (s.kind != SymbolKind.METHOD || s.paramTypes.isNotEmpty() || s.isExtension || s.name !in NOT_PROPERTY_NAMES) continue
            val owner = s.declaringClassFqn ?: continue
            if ("$owner.${s.name}" in NOT_PROPERTIES) accessedProperty(s.name)?.let { (notProperties ?: HashSet<String>().also { notProperties = it }).add(it) }
        }
        // An accessor is left to its property however the prefix reads, as IntelliJ's K2 completion has it: the
        // property answers to the accessor's name (`a.getTe` and `a.setTe` offer `text`, see [accessorMatches]).
        return kept.filterNot { s ->
            val np = notProperties
            when {
                np != null && s.kind == SymbolKind.FIELD && !s.origin.fromSource && s.name in np -> true
                s.kind != SymbolKind.METHOD || s.isExtension -> false
                np != null && s.paramTypes.isEmpty() && accessedProperty(s.name) in np -> false
                else -> isAccessorOf(s, properties)
            }
        }
    }

    /**
     * Properties the symbol model derives from a Java SETTER alone (`textColor` from `setTextColor`, `enabled`
     * from `setEnabled` beside `isEnabled()`). Kotlin has no set-only properties, so `a.textColor = …` does not
     * compile; offering one would also hide the real `setTextColor(...)` behind it.
     */
    private fun setterOnlyProperties(raw: List<KotlinSymbol>, gettable: Map<String, KotlinSymbol>): Set<KotlinSymbol> {
        val setters = HashSet<String>()
        for (s in raw) if (s.kind == SymbolKind.METHOD && !s.isExtension && s.paramTypes.size == 1 && s.name.startsWith("set")) setters.add(s.name)
        if (setters.isEmpty()) return emptySet()
        val out = HashSet<KotlinSymbol>()
        for (s in raw) {
            if (s.kind != SymbolKind.FIELD || s.isExtension || s.origin.fromSource || s.name in gettable || s.name.isEmpty()) continue
            if ("set" + s.name[0].uppercaseChar() + s.name.substring(1) in setters) out.add(s)
        }
        return out
    }

    /**
     * The properties among [raw] that stand for a Java getter, by name. Kotlin only makes a property out of a
     * getter (`getText()` -> `text`), so a property with no getter behind it (`onClickListener`, which View
     * only has a setter for) must not hide `setOnClickListener`.
     */
    private fun gettableProperties(raw: List<KotlinSymbol>): Map<String, KotlinSymbol> {
        val getters = HashSet<String>()
        for (s in raw) if (s.kind == SymbolKind.METHOD && !s.isExtension && s.paramTypes.isEmpty()) getters.add(s.name)
        if (getters.isEmpty()) return emptyMap()
        val out = HashMap<String, KotlinSymbol>()
        for (s in raw) {
            if (s.kind != SymbolKind.FIELD || s.isExtension || s.name in out) continue
            val n = s.name
            val getter = if (n.length > 2 && n.startsWith("is") && n[2].isUpperCase()) n else "get" + n[0].uppercaseChar() + n.substring(1)
            if (getter in getters) out[n] = s
        }
        return out
    }

    /** The Kotlin property a Java accessor name stands for (`getText`/`setText` -> `text`, `isShown` -> itself),
     *  or null when [name] isn't an accessor's. */
    private fun accessedProperty(name: String): String? {
        fun decap(rest: String) = rest.takeIf { it.isNotEmpty() && it[0].isUpperCase() }?.let { it[0].lowercaseChar() + it.substring(1) }
        return when {
            name.startsWith("get") || name.startsWith("set") -> decap(name.substring(3))
            name.length > 2 && name.startsWith("is") && name[2].isUpperCase() -> name
            else -> null
        }
    }

    private fun isAccessorOf(m: KotlinSymbol, properties: Map<String, KotlinSymbol>): Boolean {
        val n = m.name
        fun propertyName(stripped: String): String? =
            stripped.takeIf { it.isNotEmpty() && it[0].isUpperCase() }?.let { it[0].lowercaseChar() + it.substring(1) }
        return when {
            m.paramTypes.isEmpty() && n.startsWith("get") -> propertyName(n.substring(3))?.let { it in properties } == true
            m.paramTypes.isEmpty() && n.startsWith("is") -> n in properties
            // Only a `void` setter makes a property writable: `JavaClass setSomething(int)` stays a method.
            m.paramTypes.size == 1 && n.startsWith("set") && (m.type as? KotlinType)?.qualifiedName.let { it == null || it == "kotlin.Unit" } -> {
                val stripped = n.substring(3)
                val property = propertyName(stripped)?.let { properties[it] } ?: properties["is$stripped"] ?: return false
                val propertyType = (property.type as? KotlinType)?.qualifiedName
                val paramType = (m.paramTypes[0] as? KotlinType)?.qualifiedName
                propertyType != null && propertyType == paramType
            }
            else -> false
        }
    }

    /** Whether a candidate's (return/declared) type is assignable to the [expected] type — boosts it in [rank]. */
    private fun matchesExpected(s: KotlinSymbol, expected: KotlinType): Boolean {
        val t = s.type as? KotlinType ?: return false
        return typeFits(t, expected)
    }

    /** Whether a value of type [t] can be passed where [expected] is wanted, nullability included. */
    private fun typeFits(t: KotlinType, expected: KotlinType): Boolean {
        // `Nothing` is assignable to every type, so a `TODO()` or a `fail()` would otherwise top every slot.
        if (t.qualifiedName == "kotlin.Nothing" && !t.nullable) return false
        if (t.nullable && !expected.nullable && !expected.isTypeParameter) return false
        if (t.qualifiedName == expected.qualifiedName) return true
        return runCatching { expected.isAssignableFrom(t) }.getOrDefault(false)
    }

    // --- auto-import (symbol -> FQN; the visibility/anchor work lives in KotlinAutoImport) ---

    private fun importEditFor(s: KotlinSymbol, autoImport: KotlinAutoImport): List<TextEdit> {
        val fqn = importFqnOf(s) ?: return emptyList()
        return autoImport.editForType(fqn)
    }

    /** The scope a top-level classifier is visible through (see [KotlinAutoImport.scopeOf]); a nested one, which
     *  is reached through its outer class, after them all. */
    private fun topLevelClassifierScope(s: KotlinSymbol, autoImport: KotlinAutoImport): Int {
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return 0
        val pkg = s.packageName ?: fqn.substringBeforeLast('.', "")
        if (fqn != (if (pkg.isEmpty()) s.name else "$pkg.${s.name}")) return 6
        return autoImport.scopeOf(pkg, s.name, s.origin.fromSource)
    }

    /**
     * Whether [s] ranks as deprecated (IntelliJ's `DeprecatedWeigher`, which asks the symbol's deprecation status):
     * a `@Deprecated` declaration, a source class included, unless its `@DeprecatedSinceKotlin` versions all lie
     * ahead of the language version (`warningSince = "999.999"` deprecates nothing yet). Reads the declaration
     * only for a source symbol that may be deprecated, so a page of library candidates costs nothing.
     */
    private fun deprecatedForRanking(s: KotlinSymbol): Boolean {
        if (!s.origin.fromSource || (!s.isDeprecated && s.kind !in TYPE_KINDS)) return s.isDeprecated
        val decl = (runCatching { s.declaration() }.getOrNull() as? KotlinDomNode)?.psi as? KtDeclaration ?: return s.isDeprecated
        val entries = decl.annotationEntries
        if (entries.none { it.shortName?.asString() == "Deprecated" }) return s.isDeprecated
        val since = entries.firstOrNull { it.shortName?.asString() == "DeprecatedSinceKotlin" } ?: return true
        val versions = since.valueArguments.mapNotNull { a ->
            a.getArgumentExpression()?.text?.trim()?.removeSurrounding("\"")
        }
        if (versions.isEmpty()) return true
        return versions.any { v -> compareVersions(v, CURRENT_LANGUAGE_VERSION) <= 0 }
    }

    /** `1.9` against `2.0` by numeric components; a missing component counts as 0. */
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Which implicit receiver a member read by a bare name comes through, the innermost first: the scope index
     * IntelliJ's `CallableWeigher` compares between the kind and the member/extension split
     * (`CallableMetadataProvider.callableWeightByReceiver`). A member every receiver has (`equals`) gets no index
     * and goes last. The receivers' supertype chains are walked once, on the first member asked about, and only
     * when there is more than one receiver to choose between.
     */
    private inner class ImplicitReceiverLevels(private val resolver: KotlinResolver, private val offset: Int) {
        private val chains: List<List<String>> by lazy {
            val receivers = runCatching { resolver.implicitReceiversAt(offset) }.getOrDefault(emptyList())
            if (receivers.size < 2) emptyList() else receivers.map { supertypeChain(it) }
        }

        fun levelOf(s: KotlinSymbol): Int {
            if (s.isExtension || (s.kind != SymbolKind.METHOD && s.kind != SymbolKind.FIELD)) return 0
            val owner = s.declaringClassFqn?.replace('$', '.') ?: ((s.owner as? KotlinSymbol)?.type as? KotlinType)?.qualifiedName
                ?: s.owner?.name ?: return 0
            if (chains.isEmpty()) return 0
            fun names(fqn: String) = owner == fqn || ('.' !in owner && fqn.substringAfterLast('.') == owner)
            val levels = chains.indices.filter { i -> chains[i].any(::names) }
            if (levels.isEmpty()) return 0
            return if (levels.size == chains.size || s.name in OBJECT_METHODS) MAX_CHAIN else levels.first()
        }
    }

    /** The declared type of a receiver that names a parameter or a local (`p` in `p.`), or null. */
    private fun declaredTypeOf(receiver: KtExpression, resolver: KotlinResolver): KotlinType? {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        return runCatching { resolver.scopeSymbolsAt(receiver.textRange.startOffset, name, exactName = true) }.getOrDefault(emptyList())
            .firstOrNull { it.name == name && (it.kind == SymbolKind.PARAMETER || it.kind == SymbolKind.LOCAL_VARIABLE) }
            ?.type as? KotlinType
    }

    /**
     * The element type an annotation argument's array literal holds (`@A(enums = [<caret>])` on an
     * `Array<MyEnum>` parameter -> `MyEnum`), from the parameter the argument fills; null elsewhere.
     */
    private fun annotationArrayElementType(leaf: KtElement?, resolver: KotlinResolver): KotlinType? {
        val literal = climbTo<KtCollectionLiteralExpression>(leaf) ?: return null
        val arg = literal.parent as? KtValueArgument ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        val entry = list.parent as? KtAnnotationEntry ?: return null
        val params = runCatching { resolver.annotationParameters(entry) }.getOrDefault(emptyList())
        val param = arg.getArgumentName()?.asName?.identifier?.let { n -> params.firstOrNull { it.name == n } }
            ?: params.getOrNull(list.arguments.indexOf(arg))
            ?: return null
        val type = param.type ?: return null
        (type.typeArguments.firstOrNull() as? KotlinType)?.takeIf { type.qualifiedName == "kotlin.Array" }?.let { return it }
        val element = PRIMITIVE_ARRAY_ELEMENTS[type.qualifiedName] ?: return null
        return service.typeByFqn(element)
    }

    /**
     * The values in scope whose type is an extension function type the receiver can be passed to (`foo: Int.(String)
     * -> Char` after `i.`), which are called like members: `i.foo("")`. IntelliJ offers them on the receiver (its
     * callable contributor treats such a value as a functional-variable call with that receiver). An extension
     * property is not one of them: it needs its own receiver. Inserted as a call, the caret inside its parentheses
     * when it takes arguments. Read from the scope the caret's bare names see, filtered by the typed prefix.
     */
    private fun extensionFunctionValueItems(
        recvType: KotlinType, offset: Int, matcher: PrefixMatcher, resolver: KotlinResolver,
        /** The receiver's declared type when a smart cast narrows it (`p is J` keeps `p: I`), which fits too. */
        declared: KotlinType? = null,
    ): List<CompletionItem> {
        val receiver = recvType.withNullable(false)
        val receivers = listOfNotNull(receiver, declared?.withNullable(false))
        // Values a user declares (locals, parameters, properties, the project's top-level ones): a library's
        // top-level property of an extension function type is not worth listing every library function for,
        // which an empty prefix after the dot otherwise did on every `x.`.
        val values = runCatching { resolver.scopeSymbolsAt(offset, matcher.prefix, classpathTopLevel = false) }.getOrDefault(emptyList()) +
            contextParametersInScope(resolver.elementAt(offset), matcher, resolver)
        val out = ArrayList<CompletionItem>()
        val seen = HashSet<String>()
        for (v in values) {
            if (v.isExtension || (v.kind != SymbolKind.LOCAL_VARIABLE && v.kind != SymbolKind.PARAMETER && v.kind != SymbolKind.FIELD)) continue
            val t = v.type as? KotlinType ?: continue
            if (!t.isExtensionFunctionType || t.typeArguments.size < 2 || !matcher.matches(v.name) || !seen.add(v.name)) continue
            val wanted = (t.typeArguments[0] as? KotlinType)?.withNullable(false) ?: continue
            val fits = wanted.qualifiedName == "kotlin.Any" || receivers.any { r ->
                wanted.qualifiedName == r.qualifiedName || runCatching { wanted.isAssignableFrom(r) }.getOrDefault(false)
            }
            if (!fits) continue
            val params = t.typeArguments.subList(1, t.typeArguments.size - 1)
            val call = v.name + "()"
            out += CompletionItem(
                label = v.name,
                insertText = call,
                kind = CompletionItemKind.METHOD,
                detail = (t.typeArguments.last() as? KotlinType)?.toString(),
                symbol = v,
                caret = if (params.isEmpty()) CaretAction.AtEnd else CaretAction.At(v.name.length + 1),
                relevance = CompletionRelevance(proximity = PROXIMITY_MEMBER),
            )
        }
        return out
    }

    /**
     * IntelliJ's bracket-operator items (`createOperatorLookupElement` in the K2 contributors' utils): after
     * `receiver.` with nothing typed, an `operator fun get`/`set` is also offered as `[]` and an `operator fun
     * invoke` as `()`, accepted by replacing the `.` with the brackets, the caret inside; they rank first
     * (`ItemPriority.BRACKET_OPERATOR`). Not after `?.`, where the operator call cannot be written. The operator
     * modifier is read from a project declaration; a library one counts when the stdlib declares it (`List.get`,
     * a function type's `invoke`), as Java methods are not operators by declaration.
     */
    private fun bracketOperatorItems(
        nameRef: KtNameReferenceExpression?, raw: List<KotlinSymbol>, text: String, offset: Int, followingChar: Char?,
    ): List<CompletionItem> {
        val q = nameRef?.parent as? KtDotQualifiedExpression ?: return emptyList()
        if (q.selectorExpression !== nameRef || text.getOrNull(offset - 1) != '.') return emptyList()
        val out = ArrayList<CompletionItem>()
        val seen = HashSet<String>()
        for (sym in raw) {
            if (sym.kind != SymbolKind.METHOD) continue
            val arity = maxOf(sym.paramNames.size, sym.paramTypes.size)
            val brackets = when (sym.name) {
                "get" -> if (arity >= 1) "[]" else null
                "set" -> if (arity >= 2) "[]" else null
                "invoke" -> "()"
                else -> null
            } ?: continue
            if (!isOperatorFunction(sym) || !seen.add(sym.name + "#" + (sym.signature ?: ""))) continue
            val base = KotlinCompletionItems.toItem(sym, emptyList(), followingChar, CompletionRelevance(contextBoost = true, proximity = PROXIMITY_MEMBER))
            out += base.copy(
                label = brackets + base.label.removePrefix(sym.name),
                insertText = brackets,
                caret = CaretAction.At(1),
                additionalEdits = listOf(TextEdit(TextRange(offset - 1, offset), "")),
                symbol = null,
            )
        }
        return out
    }

    /** Whether [s] is declared `operator`: read from a project declaration, assumed for the stdlib's own. */
    private fun isOperatorFunction(s: KotlinSymbol): Boolean {
        if (s.origin.fromSource) {
            val decl = (runCatching { s.declaration() }.getOrNull() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return false
            return decl.hasModifier(KtTokens.OPERATOR_KEYWORD)
        }
        // A builtin member (`List.get`, `Function1.invoke`) arrives without a JVM owner, or under its bare
        // builtin name; a Java method carries its class's qualified name.
        val owner = s.declaringClassFqn ?: s.packageName ?: return true
        return owner.startsWith("kotlin.") || '.' !in owner
    }

    /** What `super.` offers (see [superMemberItems]): the members to rank as usual, the names among them offered
     *  per supertype instead, and the finished items for those and for the pass-through calls. */
    private class SuperItems(val members: List<KotlinSymbol>, val replaced: Set<String>, val items: List<CompletionItem>)

    /**
     * IntelliJ's `K2SuperMemberCompletionContributor` for a bare `super.` ([nameRef] its selector): the members of
     * every supertype the enclosing class lists, abstract ones left out where there are several supertypes; a name
     * more than one supertype provides (`Any`'s members aside, which never need it) is offered once per supertype,
     * inserted through `super<T>.` ([SuperItems.replaced]); and for each override around the caret, a call to the
     * member it overrides passing its own parameters (`super.foo(j)`), ranked first. Null for anything but an
     * unqualified, unlabeled `super` in a class with known supertypes.
     */
    private fun superMemberItems(
        nameRef: KtNameReferenceExpression?, matcher: PrefixMatcher, resolver: KotlinResolver, followingChar: Char?,
    ): SuperItems? {
        val q = nameRef?.parent as? KtDotQualifiedExpression ?: return null
        if (q.selectorExpression !== nameRef) return null
        val sup = q.receiverExpression as? KtSuperExpression ?: return null
        if (sup.superTypeQualifier != null || sup.getLabelName() != null) return null
        val cls = climbTo<KtClassOrObject>(sup) ?: return null
        val supertypes = cls.superTypeListEntries.mapNotNull { e ->
            val text = e.typeReference?.text ?: return@mapNotNull null
            runCatching { service.typeFromText(text, resolver.fileContext, cls.fqName?.asString()) }.getOrNull()
        }.distinctBy { it.qualifiedName }
        if (supertypes.isEmpty()) return null
        val prefix = matcher.prefix
        val several = supertypes.size > 1
        val perType = supertypes.map { t ->
            t to service.membersForCompletion(t.qualifiedName, t.typeArguments, prefix).filter { m ->
                !m.isExtension && matcher.matches(m.name) && (!several || Modifier.ABSTRACT !in m.modifiers)
            }
        }
        // A name counts once per supertype it is reached through; `Any`'s members are one symbol everywhere.
        val counts = HashMap<String, Int>()
        for ((_, members) in perType) for (name in members.filter { it.name !in OBJECT_METHODS }.map { it.name }.toSet()) {
            counts[name] = (counts[name] ?: 0) + 1
        }
        val replaced = counts.filterValues { it > 1 }.keys
        fun key(m: KotlinSymbol) = m.name + "#" + m.kind + "#" + (m.signature ?: "")
        val seen = HashSet<String>()
        val all = perType.flatMap { it.second }.filter { m -> seen.add(key(m)) }
        // A Java getter a Kotlin property stands for is left to the property, as on any receiver.
        val members = hideAccessorDuplicates(all, prefix, gettableProperties(all))
        val hidden = if (members.size == all.size) emptySet() else (all - members.toSet()).mapTo(HashSet(), ::key)
        val superRange = TextRange(sup.textRange.startOffset, sup.textRange.endOffset)
        // The overrides being written around the caret, innermost first, with the arguments that pass their
        // parameters on (a vararg spread).
        val overrides = ArrayList<Pair<KtNamedFunction, List<String>>>()
        forEachAncestor(nameRef) { n ->
            val fn = n as? KtNamedFunction ?: return@forEachAncestor
            if (!fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return@forEachAncestor
            val args = fn.valueParameters.mapNotNull { p -> p.name?.let { if (p.isVarArg) "*$it" else it } }
            if (args.size == fn.valueParameters.size) overrides += fn to args
        }
        val items = ArrayList<CompletionItem>()
        val withArgs = ArrayList<CompletionItem>()
        val relevance = CompletionRelevance(proximity = PROXIMITY_MEMBER)
        for ((type, list) in perType) {
            val qualifier = type.qualifiedName.substringAfterLast('.')
            for (m in list) {
                if (key(m) in hidden) continue
                val disambiguate = several && m.name in replaced
                val edits = if (disambiguate) listOf(TextEdit(superRange, "super<$qualifier>")) else emptyList()
                fun decorate(item: CompletionItem) =
                    if (disambiguate) item.copy(
                        additionalEdits = item.additionalEdits + edits,
                        detail = listOfNotNull(item.detail, "for $qualifier").joinToString(" "),
                        // One item per supertype: the container keeps them apart where items are de-duplicated.
                        container = type.qualifiedName,
                    )
                    else item
                if (disambiguate) {
                    val plain = decorate(KotlinCompletionItems.toItem(m, emptyList(), followingChar, relevance))
                    KotlinCompletionItems.trailingLambdaVariant(m, plain, followingChar)?.let { items += decorate(it) }
                    items += plain
                }
                // The pass-through call: a function with parameters that an override around the caret matches.
                if (m.kind != SymbolKind.METHOD || m.paramNames.isEmpty()) continue
                val match = overrides.firstOrNull { (fn, _) -> fn.name == m.name && fn.valueParameters.size == m.paramNames.size } ?: continue
                if (!disambiguate && withArgs.any { it.label.substringBefore('(') == m.name }) continue
                val call = m.name + "(" + match.second.joinToString(", ") + ")"
                withArgs += decorate(
                    KotlinCompletionItems.toItem(m, emptyList(), followingChar, relevance.copy(contextBoost = true)).copy(
                        label = call, insertText = call, caret = CaretAction.AtEnd,
                    ),
                )
            }
        }
        if (withArgs.isEmpty() && replaced.isEmpty()) return SuperItems(members, emptySet(), emptyList())
        return SuperItems(members, if (several) replaced else emptySet(), withArgs + items)
    }

    /**
     * The functions around [leaf], by name and parameter count: such a function, or a member one of them
     * overrides, is "contextual" (IntelliJ's `PreferContextualCallablesWeigher`: `super.fo<caret>` inside
     * `override fun foo2` offers `foo2` first, and so does a recursive call). A candidate counts when it has the
     * name and arity of one of them, which stands in for "is, or is overridden by, a callable containing the
     * position". A short parent walk; empty outside any function.
     */
    private fun contextualOverrides(leaf: KtElement?): Map<String, Set<Int>> {
        // Keyed by name, so a candidate of any other name is turned away without allocating anything.
        var out: HashMap<String, MutableSet<Int>>? = null
        forEachAncestor(leaf) { n ->
            val fn = n as? KtNamedFunction ?: return@forEachAncestor
            // An extension is never a contextual candidate (see where this is read).
            if (fn.receiverTypeReference != null) return@forEachAncestor
            val name = fn.name ?: return@forEachAncestor
            (out ?: HashMap<String, MutableSet<Int>>().also { out = it }).getOrPut(name) { HashSet() } += fn.valueParameters.size
        }
        return out ?: emptyMap()
    }

    /** The name an `import` of [s] would spell, or null when no import reaches it (see [importEditFor]). */
    private fun importFqnOf(s: KotlinSymbol): String? {
        return when {
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
            // A member extension of an `object` / `companion object` IS importable through its CONTAINER
            // (`import util.StringUtils.twice`, `import okhttp3.MediaType.Companion.toMediaType`): a singleton
            // needs no dispatch receiver, so the import alone puts it in scope. This is the one
            // member-extension shape an import reaches; a plain class's still gets no edit above.
            s.isExtension && (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) ->
                singletonContainerFqn(s)?.let { "$it.${s.name}" }

            else -> null
        }
    }

    /** The SINGLETON container an importable member extension is reached through: an `object` or `companion
     *  object` FQN (`util.StringUtils`, `okhttp3.MediaType.Companion`), in dot form (a binary's declaring class
     *  arrives `$`-nested). Null when there is no declaring container or it isn't a singleton: a regular
     *  class's member extension needs its dispatch receiver in scope, so no import can make it resolve. */
    private fun singletonContainerFqn(s: KotlinSymbol): String? {
        if (isTopLevelCallable(s)) return null // a `…Kt` facade, imported by package.name above
        val container = s.declaringClassFqn?.replace('$', '.') ?: return null
        return container.takeIf { service.isSingletonObject(it) }
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

    /** Instance receiver → non-static members (+ extensions); type receiver → statics + nested types.
     *  A `private` member is offered via `.` only when its declaring class is one the caret sits inside
     *  ([enclosing]) — Kotlin allows private access within the class (`this.field`, another instance of that
     *  class, and a nested/inner class reaching an enclosing class's privates), so completing inside the class
     *  must surface its privates while a `.` from outside still hides them. */
    private fun memberVisibleOn(s: KotlinSymbol, typeReceiver: Boolean, enclosing: EnclosingClasses): Boolean {
        if (s.kind == SymbolKind.CONSTRUCTOR) return false // never reached via `.`
        if (Modifier.PRIVATE in s.modifiers && !enclosing.declares(s)) return false
        if (s.isInternal && !s.origin.fromSource) return false // a library's `internal` isn't accessible cross-module
        val isStatic = Modifier.STATIC in s.modifiers
        return if (typeReceiver) isStatic || s.kind in TYPE_KINDS else !isStatic
    }

    /** The classes/objects [anchor] is lexically nested inside — the scope in which a `private` member is
     *  accessible via `.`. Captured by FQN (a binary member carries [KotlinSymbol.declaringClassFqn]) AND by
     *  simple name (a SOURCE member carries only a simple owner name — its `declaringClassFqn` is null), so
     *  [EnclosingClasses.declares] matches either. Anonymous objects (no name) drop out. */
    private fun enclosingClasses(anchor: KtElement): EnclosingClasses {
        val fqns = HashSet<String>()
        val simpleNames = HashSet<String>()
        generateSequence(anchor as KtElement?) { it.parent }.filterIsInstance<KtClassOrObject>().forEach { c ->
            c.fqName?.asString()?.let { fqns += it }
            c.name?.let { simpleNames += it }
        }
        return EnclosingClasses(fqns, simpleNames)
    }

    /** Enclosing-class identity used to gate private-member visibility (see [memberVisibleOn]/[enclosingClasses]). */
    private class EnclosingClasses(val fqns: Set<String>, val simpleNames: Set<String>) {
        /** Whether [s]'s declaring class is one the caret is inside — matched by FQN (binary) or simple name (source). */
        fun declares(s: KotlinSymbol): Boolean = s.declaringClassFqn in fqns || s.owner?.name in simpleNames
    }

    /** Inside the literal text of a string (suppress completion), but NOT inside a `${ }`/`$name` entry. */
    private fun insideStringLiteral(leaf: KtElement?): Boolean {
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

    /**
     * The `(typeParamCount, ctorRequiredArgs)` a TYPE candidate should insert with (consumed by
     * [KotlinCompletionItems.toItem]): the type's arity for a generic `<>`, and the required-argument count of
     * its cheapest constructor for a `()` call — a superclass in a supertype list ([superTypeEntry], any class),
     * or an instantiable class in a value position ([valuePosition], not an interface/abstract/object). Returns
     * `0 to -1` (bare type) for a non-type candidate or when a fact can't be decided, so completion never
     * regresses on an unknown type. Consults the symbol service, so it runs here rather than in the pure item
     * builder; the per-candidate cost is the same order as the auto-import lookup the loop already does.
     */
    private fun typeInsertFacts(
        s: KotlinSymbol,
        superTypeEntry: Boolean,
        valuePosition: Boolean,
        typePosition: Boolean,
        followingChar: Char?,
    ): Pair<Int, Int> {
        if (s.kind !in TYPE_KINDS) return 0 to -1
        val fqn = (s.type as? KotlinType)?.qualifiedName ?: return 0 to -1
        val ctorRequiredArgs = when {
            s.kind != SymbolKind.CLASS -> -1                        // only a class completes as a constructor call
            superTypeEntry -> ctorRequiredArgsOf(fqn, forSubclass = true) // a superclass always needs its `()`
            // A value position (`val v = Foo█`, an argument, a `return`): an instantiable class completes as a
            // constructor call. Excludes interfaces/abstract/sealed (isNonInstantiableType), objects (used as
            // the singleton, not `Object()`), classes with no constructor the caller can reach (`System`,
            // `Math`: private constructors only), and value types nobody constructs (`String`, `Int`), and backs
            // off on an unknown type. Not when a `.` follows: the user is reaching for a member/companion
            // (`Color.Red`), not constructing.
            valuePosition && followingChar != '.' && fqn !in NOT_CONSTRUCTED &&
                service.isNonInstantiableType(fqn) == false && !service.isObject(fqn) ->
                ctorRequiredArgsOf(fqn, forSubclass = false)

            else -> -1
        }
        // Angle brackets in a type position (`val x: List█`), or whenever a constructor call is being added (so
        // a generic value/superclass reads `ArrayList<>()`). A bare generic in a value position with no call
        // gets none — a lone `List<>` value is meaningless.
        val typeParamCount =
            if (typePosition || ctorRequiredArgs >= 0) service.classTypeParameters(fqn).size else 0
        return typeParamCount to ctorRequiredArgs
    }

    /**
     * `"$param.<caret>"`: a simple template entry followed by a dot completes the entry's members, as IntelliJ
     * does, by completing `"${param.<caret>}"` instead; accepting an item turns the entry into the block form
     * (`${param.length}`), since `$param.length` would print the text `.length` after the value.
     */
    private fun templateDotCompletion(document: DocumentSnapshot, original: String, offset: Int, prefix: String): CompletionResult? {
        val tokenStart = offset - prefix.length
        val head = original.substring((tokenStart - TEMPLATE_LOOKBEHIND).coerceAtLeast(0), tokenStart)
        val match = TEMPLATE_DOT.find(head) ?: return null
        val dollar = tokenStart - head.length + match.range.first
        if (original.getOrNull(dollar - 1) == '\\') return null
        val rewritten = original.substring(0, dollar + 1) + "{" + original.substring(dollar + 1, offset) + "}" + original.substring(offset)
        val snapshot = object : DocumentSnapshot {
            override val file = document.file
            override val version = document.version
            override val text: CharSequence = rewritten
            override fun length() = rewritten.length
        }
        val inner = completeInner(snapshot, offset + 1)
        fun shift(e: TextEdit) = if (e.range.start <= dollar) e else e.copy(range = TextRange(e.range.start - 1, e.range.end - 1))
        val open = TextEdit(TextRange(dollar + 1, dollar + 1), "{")
        val items = inner.items.filter { it.caret !is CaretAction.ExpandSnippet }.map { item ->
            item.copy(insertText = item.insertText + "}", additionalEdits = item.additionalEdits.map(::shift) + open)
        }
        return CompletionResult(items, inner.isIncomplete, TextRange(tokenStart, offset))
    }

    /** The non-local property whose initializer holds [leaf] directly, not inside a lambda or an object body
     *  (where it could run later and read the property). */
    private fun propertyBeingInitialized(leaf: KtElement?): KtProperty? {
        var prev: KtElement? = null
        var n: KtElement? = leaf
        while (n != null) {
            when (n) {
                is KtLambdaExpression, is KtClassBody, is KtNamedFunction, is KtPropertyAccessor -> return null
                is KtProperty -> return n.takeIf { !it.isLocal && prev != null && prev === it.initializer }
            }
            prev = n
            n = n.parent
        }
        return null
    }

    /** Whether [s] is the declaration [property] (the same source declaration, matched by where it starts). */
    private fun isDeclaredBy(s: KotlinSymbol, property: KtProperty): Boolean {
        val psi = (s.declaration() as? KotlinDomNode)?.psi as? KtProperty
        if (psi != null) return psi === property || psi.textRange.startOffset == property.textRange.startOffset
        // A top-level property reached through the package's index carries no tree: the same name, package and
        // file make it the same one.
        val file = property.parent as? KtFile ?: return false
        return s.origin.fromSource && s.owner == null && (s.packageName ?: "") == file.packageFqName.asString() &&
            s.origin.file?.path?.substringAfterLast('/')?.let { it == file.name } != false
    }

    /** Whether [nameRef], written right after a `.`, has no receiver: it is not the selector of a qualified
     *  expression or a callable reference, nor a segment of an `import`/`package` directive. */
    private fun danglingDot(nameRef: KtNameReferenceExpression): Boolean {
        if (climbTo<KtImportDirective>(nameRef) != null || climbTo<KtPackageDirective>(nameRef) != null) return false
        if (qualifiedMemberReceiver(nameRef) != null) return false
        val parent = nameRef.parent
        if (parent is KtCallableReferenceExpression || parent is KtUserType) return false
        return true
    }

    /**
     * Whether the `..` right before the token at [tokenStart] is not a range IntelliJ would complete after: not the
     * operator of a range expression at all (a class, package or variable name followed by `..`), or one whose
     * left operand has no `rangeTo` operator (a plain `fun rangeTo` without `operator`, or none). An operand of
     * an unknown type keeps completion on.
     */
    private fun strayRangeDots(text: String, tokenStart: Int, nameRef: KtNameReferenceExpression?, resolver: KotlinResolver): Boolean {
        if (tokenStart < 2 || text[tokenStart - 1] != '.' || text[tokenStart - 2] != '.') return false
        if (tokenStart >= 3 && text[tokenStart - 3] == '.') return false
        val bin = nameRef?.parent as? KtBinaryExpression
        if (bin == null || bin.operationToken != KtTokens.RANGE || bin.right !== nameRef) return true
        val left = bin.left ?: return false
        val type = runCatching { resolver.inferType(left)?.let { resolver.receiverForMembers(it, left.textRange.startOffset) } }
            .getOrNull() ?: return false
        if (type.isTypeParameter) return false
        val comparable = runCatching { service.typeByFqn("kotlin.Comparable").isAssignableFrom(type) }.getOrDefault(true)
        if (comparable) return false
        val candidates = runCatching { service.membersNamed(type.qualifiedName, type.typeArguments, "rangeTo") }.getOrDefault(emptyList())
            .filter { it.name == "rangeTo" } +
            runCatching { resolver.scopeMemberExtensions(left.textRange.startOffset, type, "rangeTo") }.getOrDefault(emptyList())
                .filter { it.name == "rangeTo" }
        // A library function is taken to be the operator; a project one is read off its declaration, and a copy
        // of it that arrives without one (the same function through another index) does not decide anything.
        val judged = candidates.filter { !it.origin.fromSource || it.declaration() is KotlinDomNode }
        if (judged.isEmpty()) return candidates.isEmpty()
        return judged.none { c ->
            val fn = (c.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction
            fn == null || fn.hasModifier(KtTokens.OPERATOR_KEYWORD)
        }
    }

    /** The constructor-call item for the class candidate [c] (see `expectedClass` in [completeInner]): labelled
     *  with the one callable constructor's parameters, or `(...)` for several; null when the class cannot be
     *  constructed here (abstract, an object, a value type, no constructor the caller can reach). */
    private fun constructorCallItem(c: Candidate, followingChar: Char?): CompletionItem? {
        val fqn = (c.symbol.type as? KotlinType)?.qualifiedName ?: return null
        if (fqn in NOT_CONSTRUCTED || service.isNonInstantiableType(fqn) != false || service.isObject(fqn)) return null
        val required = ctorRequiredArgsOf(fqn, forSubclass = false)
        if (required < 0) return null
        val params = constructorParameterLists(fqn) ?: return null
        val shown = if (params.size == 1) params.single() else "..."
        val typeParamCount = service.classTypeParameters(fqn).size
        val call = KotlinCompletionItems.toItem(
            c.symbol, c.importEdit, followingChar, c.relevance(), typeParamCount = typeParamCount, ctorRequiredArgs = required,
        )
        return call.copy(label = c.symbol.name + "(" + shown + ")", kind = CompletionItemKind.CONSTRUCTOR)
    }

    /** The parameter lists (`a: Int, b: String`) of [fqn]'s constructors a caller outside the class can call, an
     *  implicit no-arg one included; null when it has none. Filtered the way [ctorRequiredArgsOf] filters. */
    private fun constructorParameterLists(fqn: String): List<String>? {
        service.sourceClass(fqn)?.let { rc ->
            if (rc.constructors.isEmpty()) return listOf("")
            return rc.constructors.filter { it.visibility != "private" && it.visibility != "protected" }
                .map { ctor -> ctor.paramTexts.joinToString(", ") { (n, t) -> if (t == null) n else "$n: $t" } }
                .takeIf { it.isNotEmpty() }
        }
        val java = service.isJavaType(fqn)
        return service.constructorsOf(fqn).filter {
            when {
                Modifier.PRIVATE in it.modifiers || Modifier.PROTECTED in it.modifiers || it.isInternal -> false
                java -> Modifier.PUBLIC in it.modifiers
                else -> true
            }
        }.map { ctor ->
            ctor.paramTypes.indices.joinToString(", ") { i ->
                val type = (ctor.paramTypes[i] as? KotlinType)?.toString() ?: "?"
                (ctor.paramNames.getOrNull(i) ?: "p$i") + ": " + type
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** The required (non-defaulted, non-vararg) argument count of [fqn]'s cheapest constructor the completion
     *  site can call, which decides whether the caret lands inside the inserted `()`. -1 when it can call none
     *  (`System`, `Math`, a `private constructor` factory class), so the type completes bare. [forSubclass] is a
     *  supertype-list entry, which may also call a `protected` constructor. 0 when a source class declares no
     *  constructor (an implicit no-arg one). Reads the source model for a same-project class (its constructor
     *  shape isn't in the classpath index) and [KotlinSymbolService.constructorsOf] for a library binary, where
     *  a private constructor is never indexed at all: a Java class whose shape lists none has only private ones. */
    private fun ctorRequiredArgsOf(fqn: String, forSubclass: Boolean): Int {
        fun required(paramCount: Int, varargIndex: Int, hasDefault: List<Boolean>): Int {
            var req = 0
            for (i in 0 until paramCount) {
                if (i == varargIndex || hasDefault.getOrNull(i) == true) continue
                req++
            }
            return req
        }
        service.sourceClass(fqn)?.let { rc ->
            if (rc.constructors.isEmpty()) return 0 // implicit no-arg primary
            val callable = rc.constructors.filter {
                it.visibility != "private" && (forSubclass || it.visibility != "protected")
            }
            return callable.minOfOrNull { required(it.paramTexts.size, it.varargParamIndex, it.paramHasDefault) } ?: -1
        }
        val java = service.isJavaType(fqn)
        val callable = service.constructorsOf(fqn).filter {
            val protected = Modifier.PROTECTED in it.modifiers
            when {
                Modifier.PRIVATE in it.modifiers || it.isInternal -> false
                protected -> forSubclass
                // A Java constructor with no access flag is package-private: out of reach from another package.
                java -> Modifier.PUBLIC in it.modifiers
                else -> true
            }
        }
        return callable.minOfOrNull { required(it.paramTypes.size, it.varargParamIndex, it.paramHasDefault) } ?: -1
    }

    /** [nameRef] is a whole statement by itself (`System█` on its own line in a block or lambda body), not part
     *  of a larger expression whose value is used. */
    private fun isStatement(nameRef: KtNameReferenceExpression): Boolean = nameRef.parent is KtBlockExpression

    private fun inTypePosition(leaf: KtElement?): Boolean = climbTo<KtTypeReference>(leaf) != null

    /** The marker sits on an annotation's NAME (`@Comp…`, `@field:Inj…`, a type-use `@…`) — a type reference
     *  under a [KtAnnotationEntry], where only annotation classes belong. Checked only in the type-reference
     *  branch (so it can't misfire on an annotation's VALUE arguments, which are a name-reference position). */
    private fun inAnnotationNamePosition(leaf: KtElement?): Boolean = climbTo<KtAnnotationEntry>(leaf) != null

    /**
     * The annotation classes every file sees without an import (`Suppress`, `Deprecated`, `JvmStatic`,
     * `Target`): the type-name page for a short prefix holds only a handful of stdlib types, so an `@` would
     * otherwise offer only the project's own annotations. Read from the index's per-package type lists, only
     * at an annotation name.
     */
    private fun annotationsAt(text: String, tokenStart: Int, prefix: String): List<KotlinSymbol> {
        if (text.getOrNull(tokenStart - 1) != '.') {
            return DEFAULT_ANNOTATION_PACKAGES.flatMap { pkg ->
                runCatching { service.annotationTypesIn(pkg, prefix) }.getOrDefault(emptyList())
            }
        }
        var i = tokenStart - 1
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '.')) i--
        val qualifier = text.substring(i, tokenStart - 1)
        if (qualifier.isEmpty()) return emptyList()
        return runCatching { service.annotationTypesIn(qualifier, prefix) }.getOrDefault(emptyList())
    }

    private companion object {
        /** A simple string-template entry and its dotted path ending in a dot (`$param.`, `$foo.bar.`). */
        private val TEMPLATE_DOT = Regex("""\$[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\.$""")

        /** How far back [TEMPLATE_DOT] looks for the entry. */
        private const val TEMPLATE_LOOKBEHIND = 200

        /** The default-imported packages that declare annotation classes. */
        private val DEFAULT_ANNOTATION_PACKAGES = listOf("kotlin", "kotlin.annotation", "kotlin.jvm", "java.lang")

        // The classic completion dummy identifier; unlikely to collide with real code.
        const val MARKER = "IntellijIdeaRulezzz"

        /** Kotlin's operator conventions, with the syntax each one overloads (empty where it has none). */
        private val OPERATOR_NAMES = listOf(
            "compareTo" to "< > <= >=", "contains" to "in !in", "dec" to "--", "div" to "/", "divAssign" to "/=",
            "equals" to "== !=", "get" to "[...]", "getValue" to "", "hasNext" to "", "inc" to "++",
            "invoke" to "(...)", "iterator" to "", "minus" to "-", "minusAssign" to "-=", "next" to "",
            "not" to "!", "plus" to "+", "plusAssign" to "+=", "rangeTo" to "..", "rangeUntil" to "..<",
            "rem" to "%", "remAssign" to "%=", "set" to "[...] = ...", "setValue" to "", "times" to "*",
            "timesAssign" to "*=", "unaryMinus" to "-", "unaryPlus" to "+", "provideDelegate" to "",
            "component1" to "", "component2" to "", "component3" to "",
        )

        /** The Java getters IntelliJ never turns into a property (`NotPropertiesService.DEFAULT`). */
        private val NOT_PROPERTIES: Set<String> = buildSet {
            add("java.net.Socket.getInputStream"); add("java.net.Socket.getOutputStream")
            add("java.net.URLConnection.getInputStream"); add("java.net.URLConnection.getOutputStream")
            for (c in listOf("AtomicInteger", "AtomicLong")) {
                for (m in listOf("getAndIncrement", "getAndDecrement", "getAcquire", "getOpaque", "getPlain")) add("java.util.concurrent.atomic.$c.$m")
            }
            for (m in listOf("getChar", "getDouble", "getFloat", "getInt", "getLong", "getShort")) add("java.nio.ByteBuffer.$m")
        }

        /** The simple names in [NOT_PROPERTIES], probed before an owner-qualified name is built. */
        private val NOT_PROPERTY_NAMES: Set<String> = NOT_PROPERTIES.mapTo(HashSet()) { it.substringAfterLast('.') }

        /** What a same-named value that prefills a named argument (`a = a`) can be. */
        private val VALUE_KINDS = setOf(SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER, SymbolKind.FIELD)

        /** The read-only Kotlin collection a Java one is seen as from Kotlin. */
        private val JAVA_COLLECTIONS = mapOf(
            "java.lang.Iterable" to "kotlin.collections.Iterable",
            "java.util.Iterator" to "kotlin.collections.Iterator",
            "java.util.Collection" to "kotlin.collections.Collection",
            "java.util.List" to "kotlin.collections.List",
            "java.util.Set" to "kotlin.collections.Set",
            "java.util.Map" to "kotlin.collections.Map",
        )

        private val NUMERIC_TYPES = setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
            "kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte",
        )

        /** An innermost type-argument list (`<K, V>` of `Map<K, V>.`), stripped from a type's qualifier. */
        private val TYPE_ARGUMENTS = Regex("""<[^<>]*>""")

        /** The conventional one-letter names of the primitive types' values. */
        /** The most components a lambda's destructuring is offered with. */
        private const val MAX_COMPONENTS = 8

        /** What the standard library's `componentN` extensions return, by receiver, as their source spells it
         *  (`Map.Entry<K, V>.component1() = key`): a library has no body here to read the name from. */
        private val STDLIB_COMPONENT_NAMES = mapOf(
            "kotlin.collections.Map.Entry" to listOf("key", "value"),
            "java.util.Map.Entry" to listOf("key", "value"),
        )

        private val PRIMITIVE_PARAMETER_NAMES = mapOf(
            "kotlin.Int" to "i", "kotlin.Long" to "l", "kotlin.Short" to "sh", "kotlin.Byte" to "b",
            "kotlin.Char" to "c", "kotlin.Double" to "d", "kotlin.Float" to "f", "kotlin.Boolean" to "b",
        )

        /** The collection types a value of which is named after its elements, plural (`ints`). */
        private val PLURAL_TYPES = setOf(
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Collection",
            "kotlin.collections.MutableCollection", "kotlin.collections.Iterable", "kotlin.collections.Set",
            "kotlin.collections.MutableSet", "kotlin.collections.ArrayList", "kotlin.Array", "kotlin.sequences.Sequence",
        )

        /** One word of a camel-case name: `Map`, `Entry`, a run of capitals `URL`. */
        private val CAMEL_WORD = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+""")

        /** The hard keywords, which a name must be quoted to be used as (`` `fun` ``). */
        private val HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )

        /** The element type of each primitive array type. */
        private val PRIMITIVE_ARRAY_ELEMENTS = mapOf(
            "kotlin.IntArray" to "kotlin.Int", "kotlin.LongArray" to "kotlin.Long", "kotlin.ShortArray" to "kotlin.Short",
            "kotlin.ByteArray" to "kotlin.Byte", "kotlin.CharArray" to "kotlin.Char", "kotlin.BooleanArray" to "kotlin.Boolean",
            "kotlin.FloatArray" to "kotlin.Float", "kotlin.DoubleArray" to "kotlin.Double",
        )

        /** The keywords that start a compile-time constant (an annotation argument). */
        private val CONSTANT_KEYWORDS = setOf("true", "false", "if", "when")

        /** The `kotlin` array factories a constant may call (the compiler's `ARRAY_CALL_FQ_NAMES`). */
        private val ARRAY_FACTORIES = setOf(
            "arrayOf", "emptyArray", "booleanArrayOf", "byteArrayOf", "charArrayOf", "shortArrayOf", "intArrayOf",
            "longArrayOf", "floatArrayOf", "doubleArrayOf", "ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf",
        )

        /** The types whose values the compiler evaluates, and the members it folds on them. */
        private val CONSTANT_TYPES = setOf(
            "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
            "kotlin.Float", "kotlin.Double", "java.lang.String",
        )
        private val CONSTANT_OPERATORS = setOf(
            "plus", "minus", "times", "div", "rem", "unaryPlus", "unaryMinus", "compareTo", "get", "not", "and", "or",
            "xor", "inv", "shl", "shr", "ushr", "toString", "toByte", "toShort", "toInt", "toLong", "toFloat",
            "toDouble", "toChar",
        )

        /** Leading type annotations on a type reference (`@Foo Int`, `@Ann(x) T`). */
        private val TYPE_ANNOTATIONS = Regex("""^(@[\w.]+(\([^)]*\))?\s*)+""")

        /** The conventional name of a property setter's parameter. */
        private const val SETTER_PARAMETER = "value"

        /** Value types with a callable constructor that nobody means to call: completing `String` wants the
         *  type (`String.format`, `String::class`), never `String()`. The boxed Java forms come along because
         *  a mapped Kotlin type resolves its constructors through them (`kotlin.Int` → `java.lang.Integer`). */
        val NOT_CONSTRUCTED = setOf(
            "kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Char",
            "kotlin.Boolean", "kotlin.Float", "kotlin.Double",
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double",
        )

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
         *   expected-type match → match grade → @Composable boost → popularity → member group → receiver
         *   distance → in-scope → proximity → name length (not on member access, which is alphabetical) → name. Each Candidate precomputes its keys, so this only reads fields.
         */
        val RANK = Comparator<Candidate> { a, b ->
            var c = (if (a.fitsExpected) 0 else 1) - (if (b.fitsExpected) 0 else 1)
            if (c != 0) return@Comparator c
            c = a.grade - b.grade
            if (c != 0) return@Comparator c
            c = (if (a.boosted) 0 else 1) - (if (b.boosted) 0 else 1)
            if (c != 0) return@Comparator c
            c = b.popularity - a.popularity
            if (c != 0) return@Comparator c
            c = a.group - b.group
            if (c != 0) return@Comparator c
            c = a.distance - b.distance
            if (c != 0) return@Comparator c
            c = (if (a.inScope) 0 else 1) - (if (b.inScope) 0 else 1)
            if (c != 0) return@Comparator c
            c = a.importTier - b.importTier
            if (c != 0) return@Comparator c
            c = a.proximity - b.proximity
            if (c != 0) return@Comparator c
            c = a.receiverLevel - b.receiverLevel
            if (c != 0) return@Comparator c
            // Among the locals, the one declared in the innermost scope first, and among the top-level callables
            // the explicitly imported, then the package's, then the star-imported, then the default ones
            // (IntelliJ's scope index). The engine's chain ties them, so this order is what is shown.
            c = a.scopeOrder - b.scopeOrder
            if (c != 0) return@Comparator c
            // A variable before a function (`VariableOrFunctionWeigher`), which IntelliJ weighs before a contextual
            // callable (`PreferContextualCallablesWeigher`, placed before the proximity).
            c = a.functionKey - b.functionKey
            if (c != 0) return@Comparator c
            c = a.contextual - b.contextual
            if (c != 0) return@Comparator c
            // Tiebreak two otherwise-equal TYPE homonyms toward the one this project actually wants: in an
            // Android/Compose module `androidx.compose.ui.Modifier` must outrank `java.lang.reflect.Modifier`.
            c = packageRankOf(a.symbol) - packageRankOf(b.symbol)
            if (c != 0) return@Comparator c
            val an = a.symbol.name
            val bn = b.symbol.name
            c = a.lengthKey - b.lengthKey
            if (c != 0) return@Comparator c
            an.compareTo(bn)
        }

        /** Neutral package-relevance rank: non-types and ordinary library types share it, so [packageRankOf]
         *  only ever re-orders classifiers whose package is notably more (the project's own sources,
         *  androidx/Compose, kotlin) or less (JDK reflection/internal) relevant than the norm. */
        private const val PKG_NEUTRAL = 4

        /** [packageRankOf] for the JDK's reflection and internal packages, the least relevant classifiers. */
        private const val PKG_INTERNAL = 7

        /**
         * A LOW-priority ranking key (see [RANK]) that breaks ties between classifiers sharing a simple name by
         * relevance in the app's ecosystem — so completing `Modif` floats `androidx.compose.ui.Modifier` above
         * `java.lang.reflect.Modifier`. Lower sorts earlier. Only classifiers ([TYPE_KINDS]) are ranked;
         * everything else is [PKG_NEUTRAL] (types already separate from callables on `proximity`, so this never
         * reshuffles them). `androidx.*` reaches completion only in an Android module (the non-Android exclusion
         * drops it elsewhere), so preferring it here needs no extra platform check.
         *
         * The project's OWN types rank first. Ranking them at [PKG_NEUTRAL] put every stdlib classifier above
         * them, so `val b: Bo` in a module declaring `class Box` offered `Boolean` and `BooleanArray` ahead of
         * `Box`, and `Col` offered `Collection` ahead of a project `enum class Color`. A declaration the user
         * wrote is the most relevant thing a type position can offer.
         */
        private fun packageRankOf(s: KotlinSymbol): Int {
            if (s.kind !in TYPE_KINDS) return PKG_NEUTRAL
            if (s.origin.fromSource) return 0
            val pkg = (s.type as? KotlinType)?.qualifiedName?.substringBeforeLast('.', "") ?: return PKG_NEUTRAL
            return when {
                pkg.startsWith("androidx.compose") -> 1
                pkg.startsWith("androidx.") || pkg.startsWith("android.") || pkg.startsWith("com.google.android.") -> 2
                pkg == "kotlin" || pkg.startsWith("kotlin.") -> 3
                pkg.startsWith("java.lang.reflect") || pkg.startsWith("java.beans") ||
                    pkg.startsWith("sun.") || pkg.startsWith("com.sun.") || pkg.startsWith("jdk.internal") -> PKG_INTERNAL
                else -> PKG_NEUTRAL
            }
        }

        // Matches the engine's ranked cap (CompletionEngine.MAX_ITEMS) so this rank-aware inner cut never
        // drops a candidate the engine's global ranking would have kept — the engine cap is authoritative.
        const val MAX_ITEMS = 200

        // A receiver's supertype chain is walked for ranking only; past this many types nothing more is learned.
        const val MAX_CHAIN = 64

        // Cap on a type's own companion constants offered at an expected-type slot (`Color` has dozens of
        // named colors) so the popup stays readable; the typed prefix narrows them further.
        const val MAX_EXPECTED_CONSTANTS = 12

        // The proximity scale (see [proximityOf]): locals, members, top-level callables, keywords, classifiers,
        // and below them a capitalized library factory function named after a type (`String(...)`), so the type
        // outranks it.
        // The expected-type verdicts (see [expectedVerdict]) and the proximity band each non-fitting one is put
        // in, BAND apart so a band always outweighs the kind proximity inside it.
        private const val FITS = 0
        private const val FITS_NON_NULL = 1
        private const val UNTYPED = 2
        private const val MISMATCH = 3
        private const val BAND = KotlinCompletionWeighing.BAND

        /** What a contextual member after `super.` adds to its popularity key, over any member's own popularity. */
        private const val CONTEXTUAL_SUPER_BONUS = 10_000

        /** What an untyped candidate adds to its popularity key where a type is expected (see `untypedBonus`):
         *  more than deprecation takes off, less than a `when` condition's group scale. */
        private const val UNTYPED_BONUS = 5_000_000

        /** What deprecation takes off a candidate's popularity key: more than any popularity a candidate has. */
        private const val DEPRECATED_PENALTY = 1_000_000

        /** The Kotlin language version `@DeprecatedSinceKotlin` versions are compared with. */
        private const val CURRENT_LANGUAGE_VERSION = "2.4"

        private const val PROXIMITY_LOCAL = 0
        private const val PROXIMITY_MEMBER = 1
        private const val PROXIMITY_TOP_LEVEL = 2
        private const val PROXIMITY_OTHER = 3
        private const val KEYWORD_PROXIMITY = KotlinCompletionWeighing.KEYWORD_PROXIMITY
        private const val PROXIMITY_CLASSIFIER = KotlinCompletionWeighing.NAMED_ARGUMENT_PROXIMITY + 1
        const val TOP_LEVEL_CALLABLE_PROXIMITY = PROXIMITY_CLASSIFIER + 1
        private const val PROXIMITY_PACKAGE = TOP_LEVEL_CALLABLE_PROXIMITY + 1
        /** An anonymous object of the expected type: `KindWeigher`'s ANONYMOUS_OBJECT, after the classifiers. */
        private const val ANONYMOUS_OBJECT_PROXIMITY = PROXIMITY_PACKAGE + 1
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

/**
 * IntelliJ's default "match case: first letter" rule: the first letter typed must have the case of the name's
 * first letter (underscores skipped on both sides), so `s` offers `someValue` and `toString` but not `SomeType`,
 * and `S` offers `SomeType` but not `someValue`. A character without a case (a digit, an empty prefix) agrees
 * with anything.
 */
internal fun firstLetterCaseAgrees(prefix: String, name: String): Boolean {
    val p = prefix.firstOrNull { it != '_' } ?: return true
    val n = name.firstOrNull { it != '_' } ?: return true
    if (!p.isLetter() || !n.isLetter() || p.isUpperCase() == p.isLowerCase()) return true
    return p.isUpperCase() == n.isUpperCase()
}
