package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinPerf
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.parse.climbTo
import dev.ide.lang.kotlin.parse.identifierPrefixBefore
import dev.ide.lang.kotlin.interp.PreviewConstants
import dev.ide.lang.kotlin.resolve.ComposableContext
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeList
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

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val res = KotlinPerf.trace("kt.complete") { completeInner(params.document, params.offset) }
        result.addAllElements(res.items)
        result.setReplacementRange(res.replacementRange)
        if (res.isIncomplete) result.markIncomplete()
    }

    private fun completeInner(document: dev.ide.lang.incremental.DocumentSnapshot, requestedOffset: Int): CompletionResult {
        KotlinPerf.span("onBefore") { onBeforeComplete() }
        val original = document.text.toString()
        val offset = requestedOffset.coerceIn(0, original.length)
        val prefix = identifierPrefixBefore(original, offset)
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
        val kt = KotlinPerf.span("parse") { KotlinParserHost.parse(document.file.name, spliced) }
        val parsed = KotlinParsedFile(kt, document.file, document.version)
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        if (insideStringLiteral(markerLeaf)) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf)
        // A type/callable completed by simple name needs an `import` unless already visible; the auto-import
        // context answers that and in-scope candidates rank first, so accepting an unimported symbol imports it.
        val autoImport = KotlinAutoImport(kt, resolver.fileContext)

        val pos = KotlinPerf.span("candidates") {
            when (val where = classifyPosition(markerLeaf, nameRef)) {
                is CompletionPosition.MemberAccess -> memberAccessCandidates(where.receiver, offset, resolver, prefix)
                CompletionPosition.TypeReference -> PositionResult(service.typeNamesByPrefix(prefix))
                CompletionPosition.NameReference -> nameReferenceCandidates(markerLeaf, offset, prefix, resolver, autoImport)
            }
        }
        val raw = pos.raw

        // Inside a @Composable context (a `setContent`/`Column` content lambda, a @Composable function body),
        // float @Composable callables to the top — Android Studio's Compose weigher. NOT a filter: non-composable
        // code (`remember`, `println`, locals, control flow) stays available, just ranked below.
        val composableContext = KotlinPerf.span("composeCtx") {
            resolver.composableContextAt(offset) == ComposableContext.COMPOSABLE
        }

        val candidates = KotlinPerf.span("rank") { raw.asSequence()
            .filter { it.name != "_" && it.name.startsWith(prefix, ignoreCase = true) && MARKER !in it.name }
            .distinctBy { it.name + "#" + it.kind + "#" + (it.signature ?: "") }
            .map { Candidate(it, if (pos.packageCompletion) emptyList() else importEditFor(it, autoImport)) }
            .sortedWith(rank(prefix, pos.expected, composableContext))
            .take(MAX_ITEMS)
            .toList() }

        val symbolItems = candidates.map { KotlinCompletionItems.toItem(it.symbol, it.importEdit, followingChar) }

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
        return CompletionResult(items = items, isIncomplete = raw.size > MAX_ITEMS, replacementRange = replaceRange)
    }

    // --- position classification + per-position candidate sourcing ---

    /** Where the caret sits, deciding which candidate set applies (see [KotlinCompletion]'s class doc). */
    private sealed interface CompletionPosition {
        /** `receiver.<caret>` — the selector of a qualified expression. */
        class MemberAccess(val receiver: KtExpression) : CompletionPosition
        /** Inside a type reference — only classifiers belong. */
        object TypeReference : CompletionPosition
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
    )

    private fun classifyPosition(markerLeaf: PsiElement?, nameRef: KtNameReferenceExpression?): CompletionPosition = when {
        nameRef != null && isSelectorOfQualified(nameRef) ->
            CompletionPosition.MemberAccess((nameRef.parent as KtQualifiedExpression).receiverExpression)
        inTypePosition(markerLeaf) -> CompletionPosition.TypeReference
        else -> CompletionPosition.NameReference
    }

    /** `receiver.<caret>` — instance members + extensions, type-receiver statics + companion members, or, when
     *  the receiver is a pure package/FQN path, that package's sub-packages + types (inserted fully-qualified). */
    private fun memberAccessCandidates(receiver: KtExpression, offset: Int, resolver: KotlinResolver, prefix: String): PositionResult {
        val recvType = KotlinPerf.span("infer") { resolver.inferType(receiver) }
        if (recvType != null) {
            // Instance receiver (`listOf("").`) → instance members + extensions; type receiver (`Int.`) →
            // companion ("static") members + nested. Built-ins provide the real Kotlin members + companion (from
            // .kotlin_builtins), so `List.` is naturally empty and `Int.` shows MAX_VALUE. Constructors are never
            // reached via `.`. Prefix-aware: with large classpaths (Compose) a receiver's member+extension set is
            // huge, so push the typed prefix into the symbol service rather than enumerating then filtering.
            val typeReceiver = resolver.isTypeReceiver(receiver)
            val members = KotlinPerf.span("members") {
                service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
            }.filter { memberVisibleOn(it, typeReceiver) }
            // A bare `Type.` where the type has a companion object resolves to the companion instance, so the
            // companion's own members (Compose's `Color.Black`/`White`) and the extensions applicable to it
            // (`Modifier.Companion : Modifier` → `Modifier.padding`/`background`) are in scope too — instance-filtered.
            val raw = if (typeReceiver) {
                members + service.companionMembersFor(recvType.qualifiedName, prefix)
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
        return if (pkg != null) PositionResult(service.packageMembers(pkg, prefix), packageCompletion = true)
        else PositionResult(emptyList())
    }

    /** A bare name in expression/statement position: scope symbols + visible types, plus the override-stub,
     *  named-argument, and expected-type extras, and the keyword/live-template context. */
    private fun nameReferenceCandidates(
        markerLeaf: PsiElement?, offset: Int, prefix: String, resolver: KotlinResolver, autoImport: KotlinAutoImport,
    ): PositionResult {
        val extra = ArrayList<CompletionItem>()
        // Member-declaration position in a class body → offer overridable inherited members as stubs.
        if (isOverridePosition(markerLeaf)) {
            resolver.overridableMembersAt(offset)
                .filter { it.name.startsWith(prefix, ignoreCase = true) }
                .forEach { extra += KotlinCompletionItems.overrideItem(it) }
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
                .filter { it.name !in supplied && it.name.startsWith(prefix, ignoreCase = true) }
                .forEach { extra += KotlinCompletionItems.namedArgItem(it, bareName = editingName) }
        }
        // Inside an annotation's argument list → its parameter names (ranked first), plus the enum-ish constants
        // for @Preview's uiMode/device which aren't recoverable from the parameter type alone.
        extra += annotationArgExtras(markerLeaf, prefix, resolver)
        // The type the context wants → offer literals/enum constants and rank assignable candidates first.
        val expected = resolver.expectedTypeAt(offset)
        expected?.let { extra += expectedExtras(it, prefix, autoImport) }
        val raw = KotlinPerf.span("scope") { resolver.scopeSymbolsAt(offset, prefix) } +
            KotlinPerf.span("typeNames") { service.typeNamesByPrefix(prefix) }
        return PositionResult(raw, extra = extra, expected = expected, keywordContext = true)
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

    /** The call whose argument list contains [leaf] (for named-argument completion), or null. */
    private fun namedArgCallOf(leaf: PsiElement?): KtCallExpression? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return list.parent as? KtCallExpression
    }

    private fun suppliedArgNames(call: KtCallExpression): Set<String> =
        call.valueArguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()

    private val previewAnnotationNames = setOf("Preview") + PreviewConstants.builtinMultiPreviews.keys

    /**
     * Completion inside ANY annotation's argument list. At a name position, the annotation's not-yet-supplied
     * parameter names (resolved from its type, ranked first as named-argument items); for `@Preview` (or a
     * MultiPreview) they fall back to the bundled argument list when the androidx type isn't on the resolved
     * classpath. At the value of `@Preview`'s `uiMode`/`device`, the `Configuration.UI_MODE_*` / `Devices.*`
     * constants the argument accepts (not recoverable from the `Int`/`String` parameter type alone). Empty
     * outside an annotation.
     */
    private fun annotationArgExtras(leaf: PsiElement?, prefix: String, resolver: KotlinResolver): List<CompletionItem> {
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
                out += CompletionItem(insert, insert, CompletionItemKind.FIELD, detail = detail, sortPriority = -1)
            }
            when (argName) {
                "uiMode" -> PreviewConstants.uiModeConstants.keys
                    .filter { it.startsWith(prefix, ignoreCase = true) }.forEach { constItem("Configuration", it, "uiMode") }
                "device" -> PreviewConstants.deviceConstants.keys
                    .filter { it.startsWith(prefix, ignoreCase = true) }.forEach { constItem("Devices", it, "device") }
            }
        }
        // Name position (a bare argument, or editing an argument's name) → the annotation's parameter names.
        if (argName == null || editingName) {
            val supplied = list.arguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()
            val params = resolver.annotationParameters(ann)
                .filter { it.name !in supplied && it.name.startsWith(prefix, ignoreCase = true) }
            if (params.isNotEmpty()) {
                params.forEach { out += KotlinCompletionItems.namedArgItem(it, bareName = editingName) }
            } else if (short in previewAnnotationNames) {
                // The androidx @Preview type isn't on the resolved classpath (or the index is still building) —
                // fall back to the bundled argument list so @Preview completion works regardless. Same item shape
                // as a resolved named argument (label `name =`, inserts `name = `).
                PreviewConstants.previewArgNames
                    .filter { it !in supplied && it.startsWith(prefix, ignoreCase = true) }
                    .forEach { out += CompletionItem("$it =", "$it = ", CompletionItemKind.PARAMETER, detail = "@Preview", sortPriority = -1) }
            }
        }
        return out
    }

    /** Literals/constants the expected type admits: `true`/`false` for Boolean, `Enum.CONSTANT` for an enum,
     *  and the type's own companion constants (`Color.Transparent`, `Alignment.Center`) — each with an
     *  auto-import of the type when it isn't already visible. Powers value completion after `param = `. */
    private fun expectedExtras(expected: KotlinType, prefix: String, autoImport: KotlinAutoImport): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()
        val simple = expected.qualifiedName.substringAfterLast('.')
        fun constItem(c: KotlinSymbol, kind: CompletionItemKind) {
            val insert = "$simple.${c.name}"
            out += CompletionItem(
                label = insert, insertText = insert, kind = kind, detail = simple,
                sortPriority = -1, symbol = c, additionalEdits = autoImport.editForType(expected.qualifiedName),
            )
        }
        if (expected.qualifiedName == "kotlin.Boolean") {
            for (b in listOf("true", "false")) {
                if (b.startsWith(prefix, ignoreCase = true)) {
                    out += CompletionItem(b, b, CompletionItemKind.KEYWORD, sortPriority = -1)
                }
            }
        }
        val consts = service.enumConstantsOf(expected.qualifiedName)
        if (consts.isNotEmpty()) {
            consts.filter { it.name.startsWith(prefix, ignoreCase = true) }.forEach { constItem(it, CompletionItemKind.ENUM_CONSTANT) }
        } else {
            // Not an enum — offer the type's companion constants OF that type (`val Transparent: Color` on
            // `Color`'s companion), the value-class / object-constant idiom. Capped so a large palette (Color
            // has dozens) doesn't flood the popup; prefix-filtering narrows it as the user types.
            service.companionMembersFor(expected.qualifiedName, prefix)
                .filter { it.kind == SymbolKind.FIELD && (it.type as? KotlinType)?.qualifiedName == expected.qualifiedName }
                .take(MAX_EXPECTED_CONSTANTS)
                .forEach { constItem(it, CompletionItemKind.FIELD) }
        }
        return out
    }

    // --- ranking ---

    private class Candidate(val symbol: KotlinSymbol, val importEdit: List<TextEdit>)

    private fun rank(prefix: String, expected: KotlinType?, composableContext: Boolean): Comparator<Candidate> = compareBy(
        { if (expected != null && matchesExpected(it.symbol, expected)) 0 else 1 }, // expected-type matches first
        { if (it.symbol.name.startsWith(prefix)) 0 else 1 },       // case-sensitive prefix first
        { if (composableContext && it.symbol.isComposable) 0 else 1 }, // @Composable callables first in a @Composable context
        { memberGroup(it.symbol) },                                // own members > source ext > library ext > universal scope fns > Object methods
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
        const val MAX_ITEMS = 100
        // Cap on a type's own companion constants offered at an expected-type slot (`Color` has dozens of
        // named colors) so the popup stays readable; the typed prefix narrows them further.
        const val MAX_EXPECTED_CONSTANTS = 12
        val TYPE_KINDS = setOf(
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.ANNOTATION_TYPE, SymbolKind.RECORD,
        )
        // The universal `Object`/`Any` methods — always present on every receiver, so they sort to the bottom
        // of a member-access list (IntelliJ does the same) rather than competing with the type's real members.
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")
    }
}
