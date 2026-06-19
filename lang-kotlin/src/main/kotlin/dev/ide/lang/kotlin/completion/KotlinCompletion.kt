package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.ComposableContext
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile
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
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtValueArgumentName

/**
 * Kotlin code completion, using the completion-token technique: splice a dummy identifier at
 * the caret on a copy of the buffer, parse it, find the marker's element, and classify the position:
 *   • selector of a (safe-)qualified expression -> MEMBER_ACCESS -> receiver members ∪ extensions
 *   • bare name in expression position        -> NAME_REFERENCE -> scope symbols + visible types
 *   • inside a type reference                  -> TYPE_REFERENCE -> visible classifiers
 * Candidates are prefix-filtered, ranked (prefix/proximity), and mapped to neutral [CompletionItem]s.
 *
 * In expression position three extra contributions are merged ahead of the plain candidates:
 *   • named arguments  — inside a call's argument list, the callee's not-yet-supplied parameter names (`name = `)
 *   • override stubs   — at a member-declaration spot in a class body, overridable inherited members
 *                        (`override fun foo(): T { TODO(...) }`)
 *   • expected type    — `true`/`false` where a Boolean is wanted and `Enum.CONSTANT` at an enum slot, and the
 *                        ranking floats candidates whose type is assignable to the expected type first.
 */
class KotlinCompletionService(
    private val service: KotlinSymbolService,
    /** Run before each completion — the host wires this to sync the symbol model to the live editor buffers,
     *  so a declaration just typed in another open file completes here (cross-file freshness). */
    private val onBeforeComplete: () -> Unit = {},
) : CompletionService {

    override suspend fun complete(request: CompletionRequest): CompletionResult =
        dev.ide.lang.kotlin.KotlinPerf.trace("kt.complete") { completeInner(request) }

    private suspend fun completeInner(request: CompletionRequest): CompletionResult {
        dev.ide.lang.kotlin.KotlinPerf.span("onBefore") { onBeforeComplete() }
        val original = request.document.text.toString()
        val offset = request.offset.coerceIn(0, original.length)
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
        val kt = dev.ide.lang.kotlin.KotlinPerf.span("parse") { KotlinParserHost.parse(request.document.file.name, spliced) }
        val parsed = KotlinParsedFile(kt, request.document.file, request.document.version)
        val resolver = KotlinResolver(kt, parsed, service)

        val markerLeaf = kt.findElementAt(offset)
        // Don't complete inside a string literal's text (but DO inside ${ ... } template entries — that's code).
        if (insideStringLiteral(markerLeaf)) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = replaceRange)
        }
        val nameRef = climbTo<KtNameReferenceExpression>(markerLeaf)

        // A type used by simple name needs an `import` unless it's already visible (same package, a default
        // import, or already imported). That import is attached as an additionalEdit and in-scope candidates
        // rank first, so the popup reflects what is visible and accepting an unimported type auto-imports it.
        val visiblePackages = (DefaultImports.STAR_PACKAGES +
            resolver.fileContext.packageName +
            resolver.fileContext.imports.filter { it.isStar }.map { it.packageName }).toHashSet()
        val explicitImports = resolver.fileContext.imports.filter { !it.isStar }.map { it.fqn }.toHashSet()
        val anchor = importAnchorOf(kt)

        fun importEditForType(fqn: String): List<TextEdit> {
            if ('.' !in fqn) return emptyList()
            val pkg = fqn.substringBeforeLast('.')
            if (pkg in visiblePackages || fqn in explicitImports) return emptyList()
            return listOf(TextEdit(TextRange(anchor.offset, anchor.offset), anchor.prefix + "import " + fqn + anchor.suffix))
        }

        fun importEditFor(s: KotlinSymbol): List<TextEdit> {
            val fqn = when {
                s.kind in TYPE_KINDS -> (s.type as? KotlinType)?.qualifiedName
                // A top-level callable (println, listOf, ln, …) or top-level extension (`Int.dp`, `String.trim`)
                // is imported by its own FQN `package.name` — Kotlin imports the callable, not a facade class.
                // `ln`/`PI` from kotlin.math and `dp`/`sp` from compose.ui.unit aren't default-imported, so they
                // need an import on accept. A *member* extension (declared inside a class/scope, like Compose's
                // `RowScope.weight`) is NOT importable that way — its dispatch receiver must already be in scope
                // — so it gets no import; [isTopLevelCallable] keeps it out.
                s.packageName != null && (s.kind == SymbolKind.METHOD || s.kind == SymbolKind.FIELD) &&
                    (!s.isExtension || isTopLevelCallable(s)) ->
                    "${s.packageName}.${s.name}"
                else -> null
            } ?: return emptyList()
            return importEditForType(fqn)
        }

        // Extra items contributed ahead of plain symbol candidates: override stubs, named-argument labels,
        // and expected-type literals/enum constants. They carry their own insert text, so they bypass the
        // symbol→item pipeline below.
        val extra = ArrayList<CompletionItem>()
        var packageCompletion = false
        var expected: KotlinType? = null

        val raw: List<KotlinSymbol> = dev.ide.lang.kotlin.KotlinPerf.span("candidates") { when {
            nameRef != null && isSelectorOfQualified(nameRef) -> {
                val qualified = nameRef.parent as KtQualifiedExpression
                val receiver = qualified.receiverExpression
                val recvType = dev.ide.lang.kotlin.KotlinPerf.span("infer") { resolver.inferType(receiver) }
                if (recvType != null) {
                    // Instance receiver (`listOf("").`) → instance members + extensions; type receiver
                    // (`Int.`) → companion ("static") members + nested. Built-ins now provide the real Kotlin
                    // members + companion (from .kotlin_builtins), so `List.` is naturally empty and `Int.`
                    // shows MAX_VALUE. Constructors are never reached via `.`.
                    val typeReceiver = resolver.isTypeReceiver(receiver)
                    // Prefix-aware: with large classpaths (Compose) a receiver's member+extension set is huge,
                    // so push the typed prefix into the symbol service rather than enumerating then filtering.
                    val members = dev.ide.lang.kotlin.KotlinPerf.span("members") {
                        service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, prefix)
                    }.filter { memberVisibleOn(it, typeReceiver) }
                    // A bare `Type.` where the type has a companion object resolves to the companion instance,
                    // so the companion's own members (Compose's `Color.Black`/`White`) and the extensions
                    // applicable to it (`Modifier.Companion : Modifier` → `Modifier.padding`/`background`) are
                    // in scope too — instance-filtered, not static-filtered.
                    if (typeReceiver) {
                        members + service.companionMembersFor(recvType.qualifiedName, prefix)
                            .filter { memberVisibleOn(it, typeReceiver = false) }
                    } else {
                        members
                    }
                } else {
                    // Receiver is a package/FQN prefix (`java.util.`, `android.`) — complete its sub-packages
                    // + the types in it. (A type-or-instance receiver was handled above; this is the package
                    // path, inserted fully-qualified, so it needs no auto-import.)
                    val pkg = packagePathOf(receiver)
                    if (pkg != null) { packageCompletion = true; service.packageMembers(pkg, prefix) } else emptyList()
                }
            }
            inTypePosition(markerLeaf) -> service.typeNamesByPrefix(prefix)
            else -> {
                // Member-declaration position in a class body → offer overridable inherited members as stubs.
                if (isOverridePosition(markerLeaf)) {
                    resolver.overridableMembersAt(offset)
                        .filter { it.name.startsWith(prefix, ignoreCase = true) }
                        .forEach { extra += overrideItem(it) }
                }
                // Inside a call's argument list → offer the callee's not-yet-supplied parameter names. When the
                // caret is on the NAME of an argument that is ALREADY named (`foo(contai|nerColor = x)`), the
                // ` = ` is already present, so insert the bare name (the editor replaces the whole name token) —
                // no duplicated `=`. The arg being edited carries the marker, so its (now-garbled) name never
                // matches a real parameter and so doesn't suppress its own re-completion.
                namedArgCallOf(markerLeaf)?.let { call ->
                    val editingName = climbTo<KtValueArgumentName>(markerLeaf) != null
                    val supplied = suppliedArgNames(call)
                    resolver.callParameters(call)
                        .filter { it.name !in supplied && it.name.startsWith(prefix, ignoreCase = true) }
                        .forEach { extra += namedArgItem(it, bareName = editingName) }
                }
                // The type the context wants → offer literals/enum constants and rank assignable candidates first.
                expected = resolver.expectedTypeAt(offset)
                expected?.let { extra += expectedExtras(it, prefix) { fqn -> importEditForType(fqn) } }
                dev.ide.lang.kotlin.KotlinPerf.span("scope") { resolver.scopeSymbolsAt(offset, prefix) } +
                    dev.ide.lang.kotlin.KotlinPerf.span("typeNames") { service.typeNamesByPrefix(prefix) }
            }
        } }

        // Inside a @Composable context (a `setContent`/`Column` content lambda, a @Composable function body),
        // float @Composable callables to the top — Android Studio's Compose weigher. NOT a filter: non-composable
        // code (`remember`, `println`, locals, control flow) stays available, just ranked below.
        val composableContext = dev.ide.lang.kotlin.KotlinPerf.span("composeCtx") {
            resolver.composableContextAt(offset) == ComposableContext.COMPOSABLE
        }

        val candidates = dev.ide.lang.kotlin.KotlinPerf.span("rank") { raw.asSequence()
            .filter { it.name != "_" && it.name.startsWith(prefix, ignoreCase = true) && MARKER !in it.name }
            .distinctBy { it.name + "#" + it.kind + "#" + (it.signature ?: "") }
            .map { Candidate(it, if (packageCompletion) emptyList() else importEditFor(it)) }
            .sortedWith(rank(prefix, expected, composableContext))
            .take(MAX_ITEMS)
            .toList() }

        val symbolItems = candidates.map { toItem(it.symbol, it.importEdit, followingChar) }
        val items = (extra.distinctBy { it.kind to it.label } + symbolItems).take(MAX_ITEMS)
        return CompletionResult(items = items, isIncomplete = raw.size > MAX_ITEMS, replacementRange = replaceRange)
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

    private fun overrideItem(m: KotlinSymbol): CompletionItem {
        val isFun = m.kind == SymbolKind.METHOD
        val ret = (m.type as? KotlinType)?.toString()?.takeIf { it.isNotBlank() && it != "Unit" }
        val header =
            if (isFun) "override fun ${m.name}${paramListOf(m.signature)}" + (ret?.let { ": $it" } ?: "")
            else "override val ${m.name}" + (ret?.let { ": $it" } ?: "")
        val todo = "TODO(\"Not yet implemented\")"
        val insert = if (isFun) "$header {\n    $todo\n}" else "$header\n    get() = $todo"
        val sel = insert.indexOf(todo)
        return CompletionItem(
            label = header,
            insertText = insert,
            kind = if (isFun) CompletionItemKind.METHOD else CompletionItemKind.FIELD,
            detail = "override",
            sortPriority = -2,
            symbol = m,
            caret = if (sel >= 0) CaretAction.Select(sel, todo.length) else CaretAction.AtEnd,
        )
    }

    /** The call whose argument list contains [leaf] (for named-argument completion), or null. */
    private fun namedArgCallOf(leaf: PsiElement?): KtCallExpression? {
        val arg = climbTo<KtValueArgument>(leaf) ?: return null
        val list = arg.parent as? KtValueArgumentList ?: return null
        return list.parent as? KtCallExpression
    }

    private fun suppliedArgNames(call: KtCallExpression): Set<String> =
        call.valueArguments.mapNotNull { it.getArgumentName()?.asName?.identifier }.toHashSet()

    /** A named-argument label. [bareName] inserts only the parameter name (the caret is on an already-named
     *  argument, so ` = ` is present and must not be duplicated); otherwise it inserts `name = `. */
    private fun namedArgItem(p: dev.ide.lang.kotlin.resolve.KotlinResolver.ParamInfo, bareName: Boolean = false): CompletionItem = CompletionItem(
        label = "${p.name} =",
        insertText = if (bareName) p.name else "${p.name} = ",
        kind = CompletionItemKind.PARAMETER,
        detail = p.type?.let { typeLabel(it) },
        sortPriority = -1,
    )

    /** Literals/constants the expected type admits: `true`/`false` for Boolean, `Enum.CONSTANT` for an enum,
     *  and the type's own companion constants (`Color.Transparent`, `Alignment.Center`) — each with an
     *  auto-import of the type when it isn't already visible. Powers value completion after `param = `. */
    private fun expectedExtras(
        expected: KotlinType,
        prefix: String,
        importEditForType: (String) -> List<TextEdit>,
    ): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()
        val simple = expected.qualifiedName.substringAfterLast('.')
        fun constItem(c: KotlinSymbol, kind: CompletionItemKind) {
            val insert = "$simple.${c.name}"
            out += CompletionItem(
                label = insert, insertText = insert, kind = kind, detail = simple,
                sortPriority = -1, symbol = c, additionalEdits = importEditForType(expected.qualifiedName),
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

    private class Candidate(val symbol: KotlinSymbol, val importEdit: List<TextEdit>)

    private fun rank(prefix: String, expected: KotlinType?, composableContext: Boolean): Comparator<Candidate> = compareBy(
        { if (expected != null && matchesExpected(it.symbol, expected)) 0 else 1 }, // expected-type matches first
        { if (it.symbol.name.startsWith(prefix)) 0 else 1 },       // case-sensitive prefix first
        { if (composableContext && it.symbol.isComposable) 0 else 1 }, // @Composable callables first in a @Composable context
        { if (it.importEdit.isEmpty()) 0 else 1 },                 // in-scope (no import) before needs-import
        { proximity(it.symbol.kind) },                            // locals/members before library
        { it.symbol.name.length },
        { it.symbol.name },
    )

    /** Whether a candidate's (return/declared) type is assignable to the [expected] type — boosts it in [rank]. */
    private fun matchesExpected(s: KotlinSymbol, expected: KotlinType): Boolean {
        val t = s.type as? KotlinType ?: return false
        if (t.qualifiedName == expected.qualifiedName) return true
        return runCatching { expected.isAssignableFrom(t) }.getOrDefault(false)
    }

    private fun proximity(kind: SymbolKind): Int = when (kind) {
        SymbolKind.LOCAL_VARIABLE, SymbolKind.PARAMETER -> 0
        SymbolKind.FIELD, SymbolKind.METHOD -> 1
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> 3
        else -> 2
    }

    private fun toItem(s: KotlinSymbol, importEdit: List<TextEdit>, followingChar: Char? = null): CompletionItem {
        val isFunction = s.kind == SymbolKind.METHOD || s.kind == SymbolKind.CONSTRUCTOR
        val hasParams = isFunction && s.signature?.startsWith("()") == false && s.signature.startsWith("(")
        // The call syntax may already be present after the caret (`foo|(x)`, `Column| { }`) — don't add a second
        // `(`/`{`; just insert the name and let the existing arguments/lambda stand.
        val callSyntaxFollows = followingChar == '(' || followingChar == '{'
        val trailingLambda = if (isFunction && !callSyntaxFollows) trailingLambdaParam(s) else null
        val (insert, caret) = when {
            // A function whose LAST parameter is a function type → insert a trailing lambda. A Compose content
            // slot (`Column { }`) or a sole functional param (`remember { }`, `forEach { }`) takes the lambda
            // directly; otherwise the leading args come first (`items(<caret>) { }`).
            trailingLambda != null -> {
                val lambdaOnly = trailingLambda.isComposable || s.paramTypes.size == 1
                if (lambdaOnly) "${s.name} { }" to CaretAction.At(s.name.length + 2)      // `name { | }`
                else "${s.name}() { }" to CaretAction.At(s.name.length + 1)               // `name(|) { }`
            }
            isFunction && callSyntaxFollows -> s.name to CaretAction.AtEnd                 // parens/braces already there
            isFunction && hasParams -> "${s.name}()" to CaretAction.At(s.name.length + 1)  // between the parens
            isFunction -> "${s.name}()" to CaretAction.AtEnd                               // no-arg call
            else -> s.name to CaretAction.AtEnd
        }
        // Read like Kotlin source: the label is `println(message: String)` (name + params adjacent), and the
        // return/value type is the grayed detail on the right (`Unit`).
        val label = if (isFunction) s.name + paramListOf(s.signature) else s.name
        val detail = buildString {
            s.type?.let { append(typeLabel(it)) } // return type (funcs) / declared type (vals, props)
            if (s.isExtension) append(if (isEmpty()) "(extension)" else "  (extension)")
            if (importEdit.isNotEmpty()) {
                (s.type as? KotlinType)?.qualifiedName?.substringBeforeLast('.')
                    ?.let { append(if (isEmpty()) "($it)" else "  ($it)") }
            }
        }.ifBlank { null }
        return CompletionItem(
            label = label,
            insertText = insert,
            kind = itemKind(s.kind),
            detail = detail,
            documentation = s.documentation(), // javadoc/KDoc from attached sources → the popup's doc panel
            sortPriority = proximity(s.kind),
            symbol = s,
            additionalEdits = importEdit,
            caret = caret,
        )
    }

    /** The callee's LAST value parameter when it is a (non-vararg) function type — the slot a trailing lambda
     *  fills (`Column`'s `@Composable () -> Unit` content, `forEach`'s `(T) -> Unit`). Null otherwise, so only
     *  genuinely lambda-taking calls get the `{ }` insert. */
    private fun trailingLambdaParam(s: KotlinSymbol): KotlinType? {
        if (s.paramTypes.isEmpty()) return null
        if (s.varargParamIndex >= 0 && s.varargParamIndex == s.paramTypes.lastIndex) return null // last is vararg, not a lambda
        val last = s.paramTypes.last() as? KotlinType ?: return null
        return last.takeIf { TypeRendering.isFunctionType(it.qualifiedName) }
    }

    /** Extract the parameter list `(message: String)` from a `(message: String): Unit` signature. */
    private fun paramListOf(sig: String?): String {
        if (sig.isNullOrEmpty()) return "()"
        val i = sig.lastIndexOf("): ")
        return when {
            i >= 0 -> sig.substring(0, i + 1)
            sig.startsWith("(") -> sig // a constructor's "(params)" with no return
            else -> "()"
        }
    }

    /** Where to splice a new `import`: after the last import, else after the package, else at the top. */
    private data class ImportAnchor(val offset: Int, val prefix: String, val suffix: String)

    private fun importAnchorOf(kt: KtFile): ImportAnchor {
        kt.importDirectives.lastOrNull()?.let { return ImportAnchor(it.textRange.endOffset, "\n", "") }
        val pkg = kt.packageDirective
        if (pkg != null && pkg.textLength > 0 && pkg.text.isNotBlank()) {
            return ImportAnchor(pkg.textRange.endOffset, "\n\n", "")
        }
        return ImportAnchor(0, "", "\n\n")
    }

    /** A short type label for the popup: `List<String>?`, `Greeter`, … (simple name + args + nullability). */
    private fun typeLabel(type: TypeRef): String =
        (type as? KotlinType)?.toString() ?: type.qualifiedName.substringAfterLast('.')

    private fun itemKind(kind: SymbolKind): CompletionItemKind = when (kind) {
        SymbolKind.METHOD -> CompletionItemKind.METHOD
        SymbolKind.CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR
        SymbolKind.FIELD -> CompletionItemKind.FIELD
        SymbolKind.LOCAL_VARIABLE -> CompletionItemKind.VARIABLE
        SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
        SymbolKind.CLASS -> CompletionItemKind.CLASS
        SymbolKind.INTERFACE -> CompletionItemKind.INTERFACE
        SymbolKind.ENUM -> CompletionItemKind.ENUM
        SymbolKind.ENUM_CONSTANT -> CompletionItemKind.ENUM_CONSTANT
        SymbolKind.ANNOTATION_TYPE -> CompletionItemKind.ANNOTATION_TYPE
        SymbolKind.RECORD -> CompletionItemKind.RECORD
        SymbolKind.PACKAGE -> CompletionItemKind.PACKAGE
        SymbolKind.TYPE_PARAMETER -> CompletionItemKind.TYPE_PARAMETER
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
    private fun packagePathOf(expr: org.jetbrains.kotlin.psi.KtExpression): String? = when (expr) {
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

    private inline fun <reified T> climbTo(start: PsiElement?): T? {
        var n = start
        while (n != null) {
            if (n is T) return n
            n = n.parent
        }
        return null
    }

    private fun identifierPrefixBefore(text: String, offset: Int): String {
        var i = offset
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        return text.substring(i, offset)
    }

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
    }
}
