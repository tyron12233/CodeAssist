package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.UsageAccess
import dev.ide.lang.highlight.UsageHighlight
import dev.ide.lang.imports.ImportOrganizerService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.completion.KotlinCompletionItems
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.BuiltinStubRenderer
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.SourceDocProvider
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import dev.ide.lang.dom.TextRange

/**
 * A parameter list rendered for a signature line: declared types where there are any, the parameter's own
 * name where there is not. Shared by quick doc and the structure view, which show the same signature.
 */
internal fun paramTypes(params: List<KtParameter>): String =
    params.joinToString(", ") { it.typeReference?.text ?: it.name ?: "" }

/** A single code action: its lightbulb [title] and the document [edits] that apply it. */
class KotlinImportFix(val title: String, val edits: List<DocumentEdit>)

/**
 * The editor features that need a resolved symbol but no build: go-to navigation, quick documentation and
 * the import/implement/suspend quick fixes.
 *
 * They were members of [KotlinSourceAnalyzer], which is the JVM-only adapter binding the Kotlin backend to
 * a `CompilationContext` — a module of a real build. Not one line of what is here needs that: every one of
 * these reads a parse and asks [service], both of which build for every target. Being in the adapter is
 * simply where they were written, and it is why an iOS editor with working completion and diagnostics had
 * no go-to-definition, no quick doc and no "Import ..." on the unresolved reference it had just reported.
 *
 * The host supplies the four things it owns:
 * - [parsedFor], its cache of the last parse per file. Every entry point here works off the parse the
 *   editor already has rather than reparsing, which is what keeps a lightbulb query off the parser.
 * - [refreshOverlay] and [syncFocal], the two freshness hooks. Go-to-definition has to reach a declaration
 *   typed seconds ago in ANOTHER open tab (the overlay) and one typed in THIS buffer (the focal source),
 *   neither of which is on disk yet. A host that passes no-ops gets navigation to saved code only.
 * - [analysis], whose diagnostics the quick fixes read: a fix is offered FOR an error, so the two must be
 *   the same pass, not two passes that might disagree about what is wrong.
 */
class KotlinEditorFeatures(
    private val service: KotlinSymbolService,
    private val analysis: IncrementalSemanticAnalysis,
    private val parsedFor: (String) -> KotlinParsedFile?,
    private val refreshOverlay: () -> Unit = {},
    private val syncFocal: (KotlinParsedFile) -> Unit = {},
) {

    /**
     * Where a type declared in the project's Java source is, or its [member] (a simple name) when given: set by
     * the host, which owns the Java side. Null when [fqn] is not a Java source type.
     */
    var javaSourceLocator: (fqn: String, member: String?) -> NavTarget? = { _, _ -> null }

    /**
     * Where Java type [fqn] declares the method [member] whose parameters fit [paramTypes] (Kotlin type names, an
     * empty entry fitting anything), or null when it declares none. Set by the host; the default asks
     * [javaSourceLocator], which matches by name alone.
     */
    var javaMemberLocator: (fqn: String, member: String, paramTypes: List<String>?) -> NavTarget? =
        { fqn, member, _ -> javaSourceLocator(fqn, member) }

    /**
     * Gutter "implementations/overrides" markers for [file]'s last parse: one per INHERITABLE type declaration
     * (an interface, or an `open`/`abstract`/`sealed` class — a `final` class / object / enum / annotation is
     * skipped, nothing can extend it) that actually has direct inheritors in the [dev.ide.index.SubtypeIndex].
     * Anchored to the type's name identifier. Only the inheritor FQNs/kinds are collected (cheap — one index
     * query per type); a click resolves a target's location via [declarationLocation]. Empty until the index
     * has built the subtype relation (navigation, so the "dumb until indexed" latency is acceptable).
     */
    fun inheritorMarkers(file: VirtualFile): List<InheritorMarker> {
        val parsed = parsedFor(file.path) ?: return emptyList()
        val out = ArrayList<InheritorMarker>()
        for (decl in parsed.ktFile.collectDescendantsOfType<KtClassOrObject>()) {
            if (!canBeInherited(decl)) continue
            val fqn = decl.fqName?.asString() ?: continue
            val anchor = decl.nameIdentifier?.textRange?.startOffset ?: continue
            val subs = service.directInheritors(fqn)
            if (subs.isEmpty()) continue
            out += InheritorMarker(
                anchor,
                isInterface = decl is KtClass && decl.isInterface(),
                targets = subs.map { InheritorTarget(it.fqn, it.kind) }.sortedBy { it.fqn },
            )
        }
        return out.sortedBy { it.offset }
    }

    /** Whether a declaration can have subtypes (so an inheritors marker is meaningful): an interface or an
     *  `open`/`abstract`/`sealed` class. A `final` class (Kotlin's default), object, enum, or annotation cannot. */
    private fun canBeInherited(d: KtClassOrObject): Boolean {
        val c = d as? KtClass ?: return false
        if (c.isInterface()) return true
        if (c.isEnum() || c.isAnnotation()) return false
        return c.hasModifier(KtTokens.OPEN_KEYWORD) ||
            c.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
            c.hasModifier(KtTokens.SEALED_KEYWORD)
    }

    /** Locate the SOURCE declaration of type [fqn] (file + its name-identifier offset) for go-to-implementation
     *  navigation, or null when [fqn] isn't declared in project source (a classpath-only inheritor has no
     *  navigable source). Parses the declaring file once (uncached) to find the offset. */
    fun declarationLocation(fqn: String): Pair<VirtualFile, Int>? {
        val psf = service.sourceFileDeclaringType(fqn) ?: return null
        val kt = dev.ide.lang.kotlin.parse.KotlinParserHost.parse(psf.file.name, psf.text)
        val decl = kt.collectDescendantsOfType<KtClassOrObject>()
            .firstOrNull { it.fqName?.asString() == fqn }
        val offset = decl?.nameIdentifier?.textRange?.startOffset ?: decl?.textRange?.startOffset ?: 0
        return psf.file to offset
    }

    /**
     * Go-to targets at [offset] for the requested [kind] — the engine behind Go to Declaration /
     * Implementation(s) / Type Declaration / Super. Resolves the reference under the caret to a [KotlinSymbol]
     * (reusing [resolve]); a target is PROJECT SOURCE when the destination is declared in the project, else a
     * compiled LIBRARY class ([libraryPath] — the host opens it read-only, showing attached source or a
     * decompiled view). Empty when nothing applies — the caller may then fall back (e.g. a resource reference
     * for Declaration).
     */
    fun navigationTargets(file: VirtualFile, text: CharSequence, offset: Int, kind: NavKind): List<NavTarget> {
        val (symbol, leaf, typeRefFqn) = resolveAt(file, text, offset) ?: return emptyList()
        return targetsFor(kind, symbol, leaf, typeRefFqn)
    }

    /**
     * The navigation actions APPLICABLE at [offset] — each [NavKind] that resolves to at least one source
     * target, paired with those targets (precomputed, so the menu shows only what's usable and a pick is
     * instant). In [NavKind] order. The caret is resolved once and reused across the four kinds.
     */
    fun navigationOptions(file: VirtualFile, text: CharSequence, offset: Int): List<Pair<NavKind, List<NavTarget>>> {
        val (symbol, leaf, typeRefFqn) = resolveAt(file, text, offset) ?: return emptyList()
        return NavKind.entries.mapNotNull { kind ->
            targetsFor(kind, symbol, leaf, typeRefFqn).takeIf { it.isNotEmpty() }?.let { kind to it }
        }
    }

    /** Resolve the reference/symbol at [offset] once (symbol + PSI leaf + the resolved FQN when the caret is on
     *  a TYPE reference), for the nav queries. Null when the buffer is empty. */
    private fun resolveAt(file: VirtualFile, text: CharSequence, offset: Int): Triple<KotlinSymbol?, KtElement?, String?>? {
        val ktFile = KotlinParserHost.parse(file.name, text)
        if (ktFile.textLength == 0) return null
        val off = offset.coerceIn(0, ktFile.textLength)
        val leaf = ktFile.findElementAt(off.coerceAtMost(ktFile.textLength - 1))
        val symbol = (resolve(KotlinParsedFile(ktFile, file, 0L).nodeAt(off)) as? ResolveResult.Resolved)
            ?.symbol as? KotlinSymbol
        return Triple(symbol, leaf, typeRefFqnAt(ktFile, leaf))
    }

    /** The resolved type FQN when [leaf] sits inside a TYPE reference (`List` in `List<Int>` → `kotlin.collections.List`),
     *  so navigation lands on the TYPE rather than a same-named callable (the `List(size, init)` factory). Null when
     *  the caret isn't on a type reference or the name doesn't resolve to a known type. */
    private fun typeRefFqnAt(ktFile: KtFile, leaf: KtElement?): String? {
        leaf ?: return null
        val userType = leaf.getParentOfType<KtUserType>() ?: return null
        val name = userType.referencedName ?: return null
        val imports = ktFile.importDirectives.mapNotNull { imp ->
            imp.importedFqName?.asString()?.let {
                dev.ide.lang.kotlin.symbols.ImportInfo(it, imp.aliasName, imp.isAllUnder)
            }
        }
        val ctx = dev.ide.lang.kotlin.symbols.FileContext(ktFile.name, ktFile.packageFqName.asString(), imports)
        val fqn = service.resolveTypeName(name, ctx) ?: return null
        return fqn.takeIf { service.isKnownType(it) || service.isBuiltinType(it) }
    }

    private fun targetsFor(kind: NavKind, symbol: KotlinSymbol?, leaf: KtElement?, typeRefFqn: String?): List<NavTarget> = when (kind) {
        NavKind.DECLARATION -> declarationTargets(symbol, typeRefFqn)
        NavKind.TYPE_DECLARATION -> typeDeclarationTargets(symbol)
        NavKind.IMPLEMENTATION -> (if (typeRefFqn == null) memberImplementationTargets(symbol, leaf) else null)
            ?: implementationTargets(typeRefFqn ?: contextTypeFqn(symbol, leaf))
        NavKind.SUPER -> superTargets(symbol, leaf)
        NavKind.USAGES, NavKind.RELATED -> emptyList()
    }

    /** Go to Declaration: a TYPE reference under the caret → its type declaration; else the resolved symbol's
     *  source declaration node, else — for a classpath symbol — its declaring type opened as a read-only library
     *  view (a member lands the caret on its own name). Lands on the name identifier, matching [declarationLocation]. */
    private fun declarationTargets(symbol: KotlinSymbol?, typeRefFqn: String? = null): List<NavTarget> {
        typeRefFqn?.let { sourceOrLibrary(it)?.let { t -> return listOf(t) } }
        symbol ?: return emptyList()
        (symbol.declaration() as? KotlinDomNode)?.takeIf { symbol.origin.fromSource }?.let { dn ->
            val at = (dn.psi as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
                ?: dn.range.start
            return listOf(NavTarget(dn.owner.file, at, symbol.name, symbol.kind.name.lowercase()))
        }
        val isType = symbol.kind == SymbolKind.CLASS || symbol.kind == SymbolKind.INTERFACE
        val owner = if (isType) symbol.type?.qualifiedName else (symbol.declaringClassFqn ?: symbol.type?.qualifiedName)
        val member = if (isType) null else symbol.name
        // A classpath symbol: navigate to the declaring type (a member opens its owner, caret on the member).
        if (!symbol.origin.fromSource) {
            owner?.let { sourceOrLibrary(it, member)?.let { t -> return listOf(t) } }
        } else {
            // A source symbol with no Kotlin declaration is a Java one, which the index describes.
            owner?.let { javaSourceLocator(it, member)?.let { t -> return listOf(t) } }
        }
        val fqn = symbol.type?.qualifiedName ?: return emptyList()
        return listOfNotNull(sourceOrLibrary(fqn))
    }

    /** Go to Type Declaration: the declaration of the resolved symbol's TYPE (`val x: Foo` → `class Foo`),
     *  in project source or a read-only library view. */
    private fun typeDeclarationTargets(symbol: KotlinSymbol?): List<NavTarget> {
        val fqn = symbol?.type?.qualifiedName ?: return emptyList()
        return listOfNotNull(sourceOrLibrary(fqn))
    }

    /** Go to Implementation(s): the direct inheritors of the type in context (via the SubtypeIndex) — each a
     *  source declaration, or a read-only library view for a classpath inheritor. */
    private fun implementationTargets(typeFqn: String?): List<NavTarget> {
        typeFqn ?: return emptyList()
        return service.directInheritors(typeFqn).mapNotNull { sub ->
            sourceOrLibrary(sub.fqn)?.let {
                if (it.kind == "library") it else it.copy(kind = sub.kind)
            }
        }.sortedBy { it.label }
    }

    /** Go to Super: for an overriding member, the same-named member in each supertype; otherwise the supertypes
     *  of the type in context. Each destination is project source, or a read-only library view. */
    private fun superTargets(symbol: KotlinSymbol?, leaf: KtElement?): List<NavTarget> {
        val enclosing = leaf?.let { it.getParentOfType<KtClassOrObject>() }
        val member = leaf?.let { it.getParentOfType<KtCallableDeclaration>() }
        if (member != null && member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
            val ownerFqn = enclosing?.fqName?.asString()
            val name = member.name
            if (ownerFqn != null && name != null) {
                val members = service.supertypesOf(ownerFqn).flatMap { st ->
                    service.membersNamed(st.qualifiedName, emptyList(), name).mapNotNull { sm ->
                        val label = "$name  ·  ${st.qualifiedName.substringAfterLast('.')}"
                        (sm.declaration() as? KotlinDomNode)?.takeIf { sm.origin.fromSource }?.let {
                            NavTarget(it.owner.file, it.range.start, label, "fun")
                        }
                        // The overridden member lives in a library supertype → open that type, caret on the member.
                            ?: if (service.isClasspathType(st.qualifiedName) || service.isBuiltinType(st.qualifiedName))
                                NavTarget(LibraryFile(libraryPath(st.qualifiedName, name)), 0, label, "library")
                            else null
                    }
                }.distinctBy { it.file.path to it.offset }
                if (members.isNotEmpty()) return members
            }
        }
        val fqn = contextTypeFqn(symbol, leaf) ?: enclosing?.fqName?.asString() ?: return emptyList()
        return service.supertypesOf(fqn).mapNotNull { st -> sourceOrLibrary(st.qualifiedName) }.sortedBy { it.label }
    }

    /** A nav target for type [fqn]: its project-SOURCE declaration if present, else a read-only DECOMPILED
     *  library view ([libraryPath]) when [fqn] is a known classpath type; null otherwise. [member] (a member
     *  simple name) refines the caret the host searches for in the opened text. */
    private fun sourceOrLibrary(fqn: String, member: String? = null): NavTarget? {
        declarationLocation(fqn)?.let { (vf, off) -> return NavTarget(vf, off, navLabel(fqn), "class") }
        javaSourceLocator(fqn, member)?.let { return it }
        // A classpath binary OR a Kotlin built-in (List/Int/String — no `.class`, reconstructed from a stub).
        if (!service.isClasspathType(fqn) && !service.isBuiltinType(fqn)) return null
        return NavTarget(LibraryFile(libraryPath(fqn, member)), 0, navLabel(fqn), "library")
    }

    /** A declaration-only Kotlin stub for a BUILT-IN type [fqn] (`kotlin.collections.List`, `kotlin.Int`, …),
     *  reconstructed from its `.kotlin_builtins` shape — the read-only "go to declaration" text for a type that
     *  has no bytecode to decompile. Null when [fqn] isn't a built-in. Called by the engine's `libraryContent`. */
    fun builtinStub(fqn: String): String? =
        service.builtinShapeFor(fqn)?.let { BuiltinStubRenderer.render(fqn, it) }

    /** The type FQN a type-scoped navigation (implementation/super) operates on: the resolved type symbol, else
     *  the class/object the caret sits in. */
    private fun contextTypeFqn(symbol: KotlinSymbol?, leaf: KtElement?): String? {
        if (symbol != null && (symbol.kind == SymbolKind.CLASS || symbol.kind == SymbolKind.INTERFACE)) {
            symbol.type?.qualifiedName?.let { return it }
        }
        return leaf?.let { it.getParentOfType<KtClassOrObject>() }
            ?.fqName?.asString()
    }

    /** `simpleName  ·  package` (or just the simple name in the default package) — a picker label for an FQN. */
    private fun navLabel(fqn: String): String {
        val simple = fqn.substringAfterLast('.')
        val pkg = fqn.substringBeforeLast('.', "")
        return if (pkg.isEmpty()) simple else "$simple  ·  $pkg"
    }

    /**
     * "Import …" quick-fixes for the unresolved references overlapping [offset] in [file]'s last parse — what
     * the editor lightbulb / Alt-Enter offers on an unimported `remember`, `mutableStateOf`, a type, etc. For
     * each `kt.unresolved` name under the caret, one fix per candidate fully-qualified name (top-level
     * callable / type), inserting `import <fqn>` after the existing imports (else the package directive, else
     * the file top). A candidate already imported contributes nothing; results are de-duplicated and capped.
     * A `kt.delegateOperator` diagnostic under the caret additionally offers imports for the delegate's missing
     * `getValue`/`setValue` operator (`import androidx.compose.runtime.getValue` for `by mutableStateOf`), and a
     * bad ARGUMENT (a type mismatch, or a named argument no in-scope overload declares) offers the unimported
     * overload of the callee that would take it, from the callee name as well as from the argument itself.
     */
    fun importFixesAt(file: VirtualFile, offset: Int): List<KotlinImportFix> {
        val parsed = parsedFor(file.path) ?: return emptyList()
        refreshOverlay(); syncFocal(parsed)
        val text = parsed.ktFile.text
        val diags = analysis.diagnostics(parsed)
        fun coversCaret(d: Diagnostic) = offset >= d.range.start && offset <= d.range.end
        val unresolved =
            diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED && coversCaret(it) }
        val delegateOps =
            diags.filter { it.code == KotlinDiagnosticCodes.DELEGATE_OPERATOR && coversCaret(it) }
        // A bad ARGUMENT can be fixable by importing an EXTENSION overload of the callee that fits it. Two
        // errors say so: an argument-type mismatch (`items(list)` binds the in-scope `LazyListScope.items(Int, …)`
        // member and mismatches, but importing `androidx.compose.foundation.lazy.items` brings the matching
        // `List` overload into scope, exactly what the compiler wants and what Android Studio offers), and a
        // named argument no in-scope overload declares (`items(items = list)`, whose `items` parameter exists
        // only on that same unimported overload).
        //
        // Both are reported ON THE ARGUMENT while the name to import is the CALLEE, so a call is a fix site
        // when the caret sits on its callee name too, not only when it sits inside the diagnostic's own range:
        // on `items(list)` the name the user wants imported is `items`, not the list passed to it.
        val calleeCall = callAtCallee(parsed.ktFile, offset)
        val argFixSites = LinkedHashMap<KtCallExpression, MutableList<Diagnostic>>()
        for (d in diags) {
            if (d.code != KotlinDiagnosticCodes.TYPE_MISMATCH &&
                d.code != KotlinDiagnosticCodes.NAMED_ARGUMENT
            ) continue
            val onCaret = coversCaret(d)
            if (!onCaret && (calleeCall == null || d.range.start < calleeCall.textRange.startOffset ||
                    d.range.end > calleeCall.textRange.endOffset)
            ) continue
            // The call the bad argument BELONGS to, not the innermost call around the diagnostic: the mismatch
            // in `items(notes())` sits inside the `notes()` call, whose own import ("Import demo.notes") fixes
            // nothing while the `items` overload that fits goes unoffered. A mismatch outside any argument
            // (`val x: Int = "s"`) keeps the innermost call, which has no importable namesake anyway.
            val el = parsed.ktFile.findElementAt(d.range.start) ?: continue
            val call = el.getParentOfType<KtValueArgument>(strict = true)
                ?.let { it.getParentOfType<KtCallExpression>(strict = true) }
                ?: el.getParentOfType<KtCallExpression>(strict = true)
                ?: continue
            // A nested call's own bad argument is not this caret's business unless the caret is inside it.
            if (!onCaret && call !== calleeCall) continue
            argFixSites.getOrPut(call) { ArrayList() } += d
        }
        if (unresolved.isEmpty() && delegateOps.isEmpty() && argFixSites.isEmpty()) return emptyList()
        val existing =
            parsed.ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<KotlinImportFix>()
        val resolver by lazy { KotlinResolver(parsed.ktFile, parsed, service) }
        fun offer(fqn: String) {
            if (fqn in existing || !seen.add(fqn)) return
            // Splice the import in sorted position (a first import lands one blank line after the package).
            val plan = KotlinImportEdits.planImport(parsed.ktFile, fqn) ?: return
            out += KotlinImportFix("Import $fqn", listOf(DocumentEdit(plan.offset, 0, plan.text)))
        }
        for (d in unresolved) {
            val name = text.substring(
                d.range.start.coerceIn(0, text.length),
                d.range.end.coerceIn(0, text.length)
            )
            val candidates = service.importCandidates(name)
            // Drop the namesakes this reference cannot reach ([deadBareExtensions]): inside `LazyColumn { }` an
            // unresolved `itemsIndexed` can only be `androidx.compose.foundation.lazy.itemsIndexed`, and making
            // the user choose between it and the `grid`/`staggeredgrid` namesakes is choosing between one fix
            // and two errors. One candidate is nothing to narrow, so it skips the receiver work entirely.
            val dead =
                if (candidates.size < 2) emptySet()
                else deadBareExtensions(name, d.range.start, parsed.ktFile, resolver)
            candidates.forEach { if (it !in dead) offer(it) }
        }
        if (delegateOps.isNotEmpty()) {
            for (prop in delegatePropertiesCovering(parsed.ktFile, offset)) {
                resolver.delegateOperatorImportCandidates(prop).forEach(::offer)
            }
        }
        // Offer the importable same-named callables of the call's callee (the unimported overload that would
        // fit). A mismatch with no callee name (`val x: Int = "s"`) offers nothing, and so does a callee with no
        // importable namesake. Only candidates that COULD take this call are offered ([couldFixCall], plus every
        // flagged named argument having to be a parameter of the candidate). An import that cannot fix
        // anything is worse than no lightbulb, since accepting it leaves the error and adds a bogus line.
        val filePackage = parsed.ktFile.packageFqName.asString()
        for ((call, ds) in argFixSites) {
            val calleeName =
                (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: continue
            val flaggedNames = ds.filter { it.code == KotlinDiagnosticCodes.NAMED_ARGUMENT }
                .map {
                    text.substring(
                        it.range.start.coerceIn(0, text.length),
                        it.range.end.coerceIn(0, text.length)
                    )
                }
            val fits = service.importableCallablesNamed(calleeName).filter { (fqn, cand) ->
                // A same-package declaration is already visible without an import, so importing it cannot
                // change how this call resolves.
                fqn.substringBeforeLast('.') != filePackage &&
                    couldFixCall(cand, call, resolver) &&
                    flaggedNames.all { n -> n in cand.paramNames }
            }
            val bare = (call.parent as? KtQualifiedExpression)?.selectorExpression !== call
            val reachable =
                if (!bare) fits
                else fits.filter { reachableBare(it.second, call.textRange.startOffset, resolver) }
                    .ifEmpty { fits }
            reachable.forEach { (fqn, _) -> offer(fqn) }
        }
        return out.take(12)
    }

    /** The call whose CALLEE NAME [offset] sits on (`ite|ms(list)`, or just past it), else null. The anchor that
     *  lets an argument error's import fix be offered from the name that needs importing. */
    private fun callAtCallee(kt: KtFile, offset: Int): KtCallExpression? =
        calleeRefAt(kt, offset) ?: calleeRefAt(kt, offset - 1)

    private fun calleeRefAt(kt: KtFile, offset: Int): KtCallExpression? {
        if (offset < 0) return null
        val el = kt.findElementAt(offset) ?: return null
        val ref = el.getParentOfType<KtNameReferenceExpression>() ?: return null
        return (ref.parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref }
    }

    /**
     * Whether [cand] is reachable from a BARE reference at [offset]: a top-level callable always is, an
     * EXTENSION only through an implicit `this` whose type its receiver accepts (`LazyListScope.items` inside
     * `LazyColumn { }`). The receiver chain is [KotlinResolver.implicitReceiversAt]'s, so a receiver the
     * parse-only model cannot pin yields no match, which callers treat as "don't narrow", never as proof.
     */
    private fun reachableBare(cand: KotlinSymbol, offset: Int, resolver: KotlinResolver): Boolean {
        val recv = cand.receiverTypeFqn ?: return true
        return resolver.implicitReceiversAt(offset).any { accepts(resolver, KotlinType(recv), it) }
    }

    /**
     * The FQNs among [name]'s import candidates that a BARE reference at [offset] provably cannot reach: the
     * extension namesakes left over once at least one extension candidate DOES match an implicit receiver in
     * scope. That one match is the evidence the receiver chain is modeled here; without it (an unmodeled
     * receiver, or none at all) nothing is dead and the set is empty, leaving every candidate offered as before.
     * Empty too for a qualified `x.foo`, where an explicit receiver decides and the implicit chain is silent.
     */
    private fun deadBareExtensions(
        name: String,
        offset: Int,
        kt: KtFile,
        resolver: KotlinResolver,
    ): Set<String> {
        val el = kt.findElementAt(offset) ?: return emptySet()
        val ref = el.getParentOfType<KtNameReferenceExpression>()
            ?: return emptySet()
        if (isQualifiedSelector(ref)) return emptySet()
        val extensions = service.importableCallablesNamed(name).filter { it.second.receiverTypeFqn != null }
        val (matching, rest) = extensions.partition { reachableBare(it.second, offset, resolver) }
        if (matching.isEmpty()) return emptySet()
        // An FQN with one reachable overload stays, whatever its namesakes on other receivers look like.
        return rest.mapTo(HashSet()) { it.first } - matching.mapTo(HashSet()) { it.first }
    }

    /** Whether [ref] is the selector of a qualified expression (`x.foo`, `x.foo()`), i.e. has an explicit receiver. */
    private fun isQualifiedSelector(ref: KtNameReferenceExpression): Boolean {
        val target: KtExpression =
            (ref.parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref } ?: ref
        val q = target.parent as? KtQualifiedExpression ?: return false
        return q.selectorExpression === target
    }

    /**
     * Whether importing [cand] could plausibly make [call] resolve — the gate on the argument-mismatch import
     * fix. The diagnostic means the arguments fit nothing currently in scope, so a same-NAMED declaration is
     * only worth offering when it could actually take them.
     *
     * Rejects on PROOF only, never on missing information — an un-inferred argument, an unmodeled parameter
     * type or an unknown receiver leaves the candidate offered, the same conservative posture the mismatch
     * check itself takes before reporting. What it does rule out:
     *  - a non-function candidate: a property or type can never accept an argument list;
     *  - a candidate unreachable the way the call is written. With an explicit receiver (`out.write(b)`) only
     *    an EXTENSION applies, and only one whose receiver type that receiver satisfies. A bare call
     *    (`items(list)`) can land on a top-level function or on an extension of an implicit receiver, so no
     *    receiver test runs here; the caller narrows a bare call's candidates against the receiver chain
     *    ([reachableBare]), which stays silent unless one of them matches it;
     *  - more positional arguments than the candidate has parameters (a vararg absorbs any number, so a
     *    vararg candidate is never rejected on count);
     *  - a positionally-typed argument its parameter provably cannot hold — the `write(text: String)` that
     *    used to be offered for `write(byteArray)`.
     */
    private fun couldFixCall(cand: KotlinSymbol, call: KtCallExpression, resolver: KotlinResolver): Boolean {
        if (cand.kind != SymbolKind.METHOD) return false
        val qualified = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
        val receiverType = qualified?.receiverExpression?.let { resolver.inferType(it) }
        if (receiverType != null && !receiverType.isTypeParameter) {
            val candReceiver = cand.receiverTypeFqn ?: return false // a member/top-level can't take `x.` here
            if (!accepts(resolver, KotlinType(candReceiver), receiverType)) return false
        }
        val args = call.valueArguments
        val vararg = cand.varargParamIndex
        val paramCount = maxOf(cand.paramTypes.size, cand.paramNames.size)
        if (vararg < 0 && args.size > paramCount) return false
        args.forEachIndexed { i, a ->
            // A named argument's slot isn't `i`, a lambda lands on the last parameter, and anything folding
            // into a vararg is open-typed — all left to the real overload resolution the import triggers.
            if (a is KtLambdaArgument || a.getArgumentName() != null) return@forEachIndexed
            if (vararg in 0..i) return@forEachIndexed
            val pt = cand.paramTypes.getOrNull(i) as? KotlinType ?: return@forEachIndexed
            val at = a.getArgumentExpression()?.let { resolver.inferType(it) } ?: return@forEachIndexed
            if (!accepts(resolver, pt, at)) return false
        }
        return true
    }

    /** [paramAcceptsArg] over JVM-canonicalized types ([canonicalTypeForCheck]), and only where both sides are
     *  modeled: an unknown classifier, a type parameter or `Nothing` is no evidence of a clash, so it accepts.
     *  Deliberately the same comparison the mismatch check makes, so the fix never contradicts the diagnostic. */
    private fun accepts(resolver: KotlinResolver, param: KotlinType, arg: KotlinType): Boolean {
        if (param.isTypeParameter || arg.isTypeParameter) return true
        if (arg.qualifiedName == "kotlin.Nothing") return true
        val p = canonicalTypeForCheck(param)
        val a = canonicalTypeForCheck(arg)
        if (!service.isKnownType(p.qualifiedName) || !service.isKnownType(a.qualifiedName)) return true
        return with(resolver) { paramAcceptsArg(p, a) }
    }

    /**
     * The "Implement members" quick-fix for a `kt.abstractNotImplemented` diagnostic anchored at [offset] (the
     * class name): generate `override` stubs for the inherited abstract members [cls] leaves unimplemented and
     * insert them into the class body (creating a `{ }` body when the class has none). Null when the class
     * isn't found or nothing is actually missing (the diagnostic is stale). The stub text is the same one
     * completion's override items use ([KotlinCompletionItems.overrideStubText]).
     */
    fun implementMembersFix(file: VirtualFile, offset: Int): KotlinImportFix? {
        val parsed = parsedFor(file.path) ?: return null
        refreshOverlay(); syncFocal(parsed)
        val ktFile = parsed.ktFile
        val cls = classCovering(ktFile, offset) ?: return null
        val missing = KotlinResolver(ktFile, parsed, service).unimplementedAbstractMembers(cls)
        if (missing.isEmpty()) return null
        val text = ktFile.text
        val baseIndent = lineIndentOf(text, cls.textRange.startOffset)
        val memberIndent = "$baseIndent    "
        val stubs = missing.joinToString("\n\n") { m ->
            memberIndent + KotlinCompletionItems.overrideStubText(m)
                .replace("\n", "\n$memberIndent")
        }
        val body = cls.body
        val edit = if (body != null) {
            val at = (body.rBrace?.textRange?.startOffset ?: body.textRange.endOffset)
            DocumentEdit(at, 0, "\n$stubs\n$baseIndent")
        } else {
            DocumentEdit(cls.textRange.endOffset, 0, " {\n$stubs\n$baseIndent}")
        }
        return KotlinImportFix("Implement members", listOf(edit))
    }

    /**
     * The `suspend`-modifier quick-fix for a `kt.suspendOverride` diagnostic anchored at [offset] (the member
     * name): add `suspend` to an override that must have it, or remove it from one that must not. The mismatch
     * is RE-DERIVED here ([KotlinResolver.suspendMismatchFor]) rather than read off the diagnostic, so a stale
     * diagnostic — the user already fixed it by hand — offers nothing instead of writing the wrong edit.
     *
     * `suspend` goes immediately before `fun`, which is both the Kotlin modifier order (`override suspend fun`)
     * and where the generated override stub puts it.
     */
    fun suspendModifierFix(file: VirtualFile, offset: Int): KotlinImportFix? {
        val parsed = parsedFor(file.path) ?: return null
        refreshOverlay(); syncFocal(parsed)
        val ktFile = parsed.ktFile
        val fn = functionCovering(ktFile, offset) ?: return null
        val resolver = KotlinResolver(ktFile, parsed, service)
        val superMember = resolver.suspendMismatchFor(fn) ?: return null
        val text = ktFile.text
        return if (superMember.isSuspend) {
            val at = (fn.funKeyword ?: return null).textRange.startOffset
            KotlinImportFix("Add 'suspend' modifier", listOf(DocumentEdit(at, 0, "suspend ")))
        } else {
            val tok = fn.modifierList?.getModifier(KtTokens.SUSPEND_KEYWORD)
                ?: return null
            val start = tok.textRange.startOffset
            var end = tok.textRange.endOffset
            while (end < text.length && text[end] == ' ') end++ // the separating space goes with the keyword
            KotlinImportFix("Remove 'suspend' modifier", listOf(DocumentEdit(start, end - start, "")))
        }
    }

    /**
     * The minSdk check's quick fixes for a diagnostic at [offset] requiring API [level]: `@RequiresApi(level)` on
     * the enclosing declaration, and an `SDK_INT` guard around the statement.
     */
    fun newApiFixes(file: VirtualFile, offset: Int, level: Int): List<KotlinImportFix> {
        val parsed = parsedFor(file.path) ?: return emptyList()
        return KotlinApiLevelFixes.fixes(parsed.ktFile, offset, level, service.isKnownType("androidx.annotation.RequiresApi"))
    }

    /** The missing-permission check's "Add permission check" fix at [offset], or null where it can't apply. */
    fun permissionCheckFix(file: VirtualFile, offset: Int, permissions: List<String>, anyOf: Boolean): KotlinImportFix? {
        val parsed = parsedFor(file.path) ?: return null
        return KotlinPermissionFixes.checkFix(parsed.ktFile, offset, permissions, anyOf, service.isKnownType("androidx.core.app.ActivityCompat"))
    }

    /**
     * The quick fixes for one of the editor's own diagnostics ([KotlinQuickFixes.CODES]) over `[start, end)`.
     * All but one are syntactic; a non-exhaustive `when` also gets "Add remaining branches", which has to
     * enumerate the subject's enum constants or sealed subclasses, offered ahead of "Add 'else' branch".
     */
    fun diagnosticFixes(file: VirtualFile, code: String, start: Int, end: Int, message: String): List<KotlinImportFix> {
        val parsed = parsedFor(file.path) ?: return emptyList()
        val ktFile = parsed.ktFile
        val fixes = KotlinQuickFixes.fixes(code, ktFile, start, end, message)
        if (code != KotlinDiagnosticCodes.WHEN_EXHAUSTIVE) return fixes
        refreshOverlay(); syncFocal(parsed)
        val whenExpr = generateSequence(ktFile.findElementAt(start)) { it.parent }
            .filterIsInstance<KtWhenExpression>()
            .firstOrNull { it.whenKeyword.textRange.startOffset == start } ?: return fixes
        val subject = whenExpr.subjectExpression ?: return fixes
        val fqn = KotlinResolver(ktFile, parsed, service).inferType(subject)?.qualifiedName ?: return fixes
        val branches = KotlinQuickFixes.missingWhenBranches(whenExpr, fqn, service)
        if (branches.isEmpty()) return fixes
        val e = KotlinQuickFixes.insertBranches(ktFile.text, whenExpr, branches) ?: return fixes
        return listOf(KotlinImportFix("Add remaining branches", listOf(e))) + fixes
    }

    /**
     * How directly [file] sees top-level [symbol] by its simple name, lower is closer: not top-level at all (a
     * local or member, already scoped), explicitly imported, the file's own package, a star or default import,
     * and last, a package the file does not import (reachable only by a fix, never by the compiler).
     */
    private fun visibilityRank(symbol: Symbol, file: KtFile): Int {
        val pkg = (symbol as? KotlinSymbol)?.packageName ?: return 0
        val imports = file.importDirectives
        if (imports.any { !it.isAllUnder && it.importedFqName?.asString() == (if (pkg.isEmpty()) symbol.name else "$pkg.${symbol.name}") }) return 1
        if (pkg == file.packageFqName.asString()) return 2
        if (imports.any { it.isAllUnder && it.importedFqName?.asString() == pkg } ||
            dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(pkg)
        ) return 3
        return 4
    }

    // ---- Find Usages / Rename -------------------------------------------------------------------------

    /**
     * The symbol Find Usages or Rename at [offset] acts on: the declaration whose name the caret is on, else
     * what the reference under the caret resolves to. Null when nothing resolves. [readSource] reads another
     * file's current text (an open buffer's, else the disk's), for a declaration that lives elsewhere.
     */
    fun usageTargetAt(
        file: VirtualFile, text: CharSequence, offset: Int, readSource: (String) -> CharSequence?,
        /** Rename takes the overloads declared beside the symbol along (see [targetFor]); Find Usages does not. */
        includeOverloads: Boolean = false,
    ): KotlinUsageTarget? {
        val ktFile = KotlinParserHost.parse(file.name, text)
        if (ktFile.textLength == 0) return null
        return usageTargetIn(KotlinParsedFile(ktFile, file, 0L), file, offset, readSource, includeOverloads)
    }

    /** [usageTargetAt] over an already-parsed [parsed] of [file]. */
    private fun usageTargetIn(
        parsed: KotlinParsedFile, file: VirtualFile, offset: Int, readSource: (String) -> CharSequence?, includeOverloads: Boolean,
    ): KotlinUsageTarget? {
        val ktFile = parsed.ktFile
        refreshOverlay(); syncFocal(parsed) // once, for the file being edited; the sweep resolves without it
        declarationNamedAt(ktFile, offset)?.let { return targetFor(it, file.path, ktFile, includeOverloads) }
        val off = offset.coerceIn(0, ktFile.textLength - 1)
        val ref = refAt(ktFile, off) ?: return null
        val (path, at) = identityAt(parsed, ref.textRange.startOffset, KotlinResolver(ktFile, parsed, service)) ?: return null
        if (isKotlinSourcePath(path)) {
            val declKt = if (path == file.path) ktFile
            else KotlinParserHost.parse(path.substringAfterLast('/'), readSource(path) ?: return null)
            declarationNamedAt(declKt, at, exact = true)?.let { return targetFor(it, path, declKt, includeOverloads) }
        }
        // Declared in Java source or a library: its usages can be found, but Rename is not this side's.
        return KotlinUsageTarget(ref.getReferencedName(), "symbol", setOf(usageIdentity(path, at)), emptyList(), null, emptySet(), null)
    }

    /** A target for a symbol declared outside Kotlin (Java source), known by its declaration's location. */
    fun usageTargetForDeclaration(path: String, offset: Int, name: String, importFqn: String?): KotlinUsageTarget =
        KotlinUsageTarget(name, "symbol", setOf(usageIdentity(path, offset)), emptyList(), null, setOfNotNull(importFqn), null)

    /**
     * Every reference to [target] in [file]'s [text], name ranges in offset order, its declarations excluded.
     * One resolver serves the whole file, and the focal file is left alone (see [resolveWith]).
     */
    fun usagesIn(file: VirtualFile, text: CharSequence, target: KotlinUsageTarget): List<TextRange> {
        val scope = target.scope
        if (scope != null && scope.first != file.path) return emptyList()
        if (!containsIdentifier(text, target.name)) return emptyList()
        val ktFile = KotlinParserHost.parse(file.name, text)
        return usageRefsIn(KotlinParsedFile(ktFile, file, 0L), text, target).map { it.first }
    }

    /**
     * The names in [file]'s [text] that refer to the symbol under [offset], each marked a read, a write or the
     * declaration, for the editor's highlight-usages-at-caret. One parse serves both the target and the sweep.
     * Null when the caret is not on a resolvable symbol.
     */
    fun usageHighlightsAt(
        file: VirtualFile, text: CharSequence, offset: Int, readSource: (String) -> CharSequence?,
    ): List<UsageHighlight>? {
        val ktFile = KotlinParserHost.parse(file.name, text)
        if (ktFile.textLength == 0) return null
        val parsed = KotlinParsedFile(ktFile, file, 0L)
        val target = usageTargetIn(parsed, file, offset, readSource, includeOverloads = false) ?: return null
        val out = ArrayList<UsageHighlight>()
        target.declarations.filter { it.first == file.path }.forEach { out += UsageHighlight(it.second, UsageAccess.DECLARATION) }
        val scope = target.scope
        if (scope == null || scope.first == file.path) {
            for ((range, ref) in usageRefsIn(parsed, text, target)) {
                out += UsageHighlight(range, if (ref != null && isWritten(ref)) UsageAccess.WRITE else UsageAccess.READ)
            }
        }
        return out.distinctBy { it.range.start }.sortedBy { it.range.start }
    }

    /** Whether [ref] is assigned: the left side of `=`/`+=`/… (bare or as `a.ref`), or the operand of `++`/`--`.
     *  Compared by range: the tree hands out a fresh wrapper per navigation, so identity says nothing. */
    private fun isWritten(ref: KtNameReferenceExpression): Boolean {
        var e: KtElement = ref
        (e.parent as? KtQualifiedExpression)?.let { q -> if (q.selectorExpression?.textRange == e.textRange) e = q }
        while (true) e = (e.parent as? KtParenthesizedExpression) ?: break
        return when (val p = e.parent) {
            is KtBinaryExpression -> p.operationToken in ASSIGN_OPS && p.left?.textRange == e.textRange
            is KtUnaryExpression -> p.operationToken in INC_DEC
            else -> false
        }
    }

    /** [usagesIn]'s sweep over an already-parsed file: each range with its reference (null for an import's). */
    private fun usageRefsIn(
        parsed: KotlinParsedFile, text: CharSequence, target: KotlinUsageTarget,
    ): List<Pair<TextRange, KtNameReferenceExpression?>> {
        val scope = target.scope
        val ktFile = parsed.ktFile
        val resolver = KotlinResolver(ktFile, parsed, service)
        val out = ArrayList<Pair<TextRange, KtNameReferenceExpression?>>()
        // Only where the name is written: walking every reference in the file forced the lazy parse to build
        // every function body in it, for the handful that can mention the name at all.
        val visited = HashSet<Int>()
        for (at in identifierOccurrences(text, target.name)) {
            val leaf = ktFile.findElementAt(at) ?: continue
            val ref = leaf as? KtNameReferenceExpression ?: leaf.getParentOfType<KtNameReferenceExpression>() ?: continue
            if (!visited.add(ref.textRange.startOffset)) continue
            if (ref.getReferencedName() != target.name) continue
            val r = ref.textRange
            if (scope != null && (r.startOffset < scope.second.start || r.endOffset > scope.second.end)) continue
            if (ref.getParentOfType<KtImportDirective>(strict = true) != null) continue // matched by FQN below
            if (ref.getParentOfType<KtPackageDirective>(strict = true) != null) continue
            val (path, at) = identityAt(parsed, r.startOffset, resolver) ?: continue
            if (usageIdentity(path, at) in target.identities) out += TextRange(r.startOffset, r.endOffset) to ref
        }
        // An import names the symbol by FQN, which resolution does not visit: its last segment is the name.
        for (imp in ktFile.importDirectives) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (fqn !in target.importFqns) continue
            val end = imp.importedReference?.textRange?.endOffset ?: continue
            val start = end - target.name.length
            if (start >= 0 && text.subSequence(start, end).toString() == target.name) out += TextRange(start, end) to null
        }
        return out.distinctBy { it.first }.sortedBy { it.first.start }
    }

    /** The declaration (file path, name offset) the reference at [offset] resolves to, through [resolver]. */
    private fun identityAt(parsed: KotlinParsedFile, offset: Int, resolver: KotlinResolver): Pair<String, Int>? {
        val node = parsed.nodeAt(offset) as? KotlinDomNode ?: return null
        val symbol = (resolveWith(node, resolver) as? ResolveResult.Resolved)?.symbol as? KotlinSymbol
        // A source declaration is read straight off the symbol, which costs no parse.
        if (symbol != null && symbol.origin.fromSource) {
            (symbol.declaration() as? KotlinDomNode)?.let { dn ->
                val at = (dn.psi as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset ?: dn.range.start
                return dn.owner.file.path to at
            }
        }
        val leaf = parsed.ktFile.findElementAt(offset)
        val t = declarationTargets(symbol, typeRefFqnAt(parsed.ktFile, leaf)).firstOrNull() ?: return null
        return t.file.path to t.offset
    }

    private fun isKotlinSourcePath(path: String): Boolean =
        !path.startsWith("library://") && (path.endsWith(".kt") || path.endsWith(".kts"))

    /** The name reference at [offset], or just before it (a caret at the end of a name). */
    private fun refAt(ktFile: KtFile, offset: Int): KtNameReferenceExpression? {
        for (o in listOf(offset, offset - 1)) {
            if (o < 0) continue
            val ref = ktFile.findElementAt(o)?.let { it as? KtNameReferenceExpression ?: it.getParentOfType<KtNameReferenceExpression>() }
            if (ref != null) return ref
        }
        return null
    }

    /**
     * The declaration whose NAME is at [offset]: the caret anywhere on it (or just past it), or with [exact],
     * the name starting exactly there (a resolved declaration location).
     */
    private fun declarationNamedAt(ktFile: KtFile, offset: Int, exact: Boolean = false): KtNamedDeclaration? {
        val probes = if (exact) listOf(offset) else listOf(offset, offset - 1)
        for (o in probes) {
            if (o < 0 || o >= ktFile.textLength) continue
            var n: KtElement? = ktFile.findElementAt(o)
            while (n != null) {
                if (n is KtNamedDeclaration) {
                    val id = n.nameIdentifier?.textRange
                    if (id != null && (if (exact) id.startOffset == offset else o >= id.startOffset && o < id.endOffset)) return n
                }
                if (n is KtBlockExpression || n is KtClassBody) break
                n = n.parent
            }
        }
        return null
    }

    /**
     * The target for a Kotlin source declaration [decl] in [path]: it and its override family, and with
     * [includeOverloads] the same-named functions declared beside it with theirs.
     *
     * Find Usages tells overloads apart (a call resolves to the overload it binds to). Rename moves them together:
     * should one call site resolve to the wrong overload, renaming a single overload would leave that call
     * broken, while renaming the set always leaves code that compiles.
     */
    private fun targetFor(decl: KtNamedDeclaration, path: String, kt: KtFile, includeOverloads: Boolean): KotlinUsageTarget? {
        val name = decl.name ?: return null
        val ids = LinkedHashSet<String>()
        val decls = ArrayList<Pair<String, TextRange>>()
        fun add(p: String, at: Int) {
            if (ids.add(usageIdentity(p, at))) decls += p to TextRange(at, at + name.length)
        }
        decl.nameIdentifier?.let { add(path, it.textRange.startOffset) } ?: return null
        val scope = scopeOf(decl, kt)
        val family = arrayListOf(decl)
        if (includeOverloads && decl is KtNamedFunction && (decl.parent is KtFile || decl.parent is KtClassBody)) {
            decl.parent?.children?.filterIsInstance<KtNamedFunction>()
                ?.filter { it !== decl && it.name == name }
                ?.forEach { f -> f.nameIdentifier?.let { add(path, it.textRange.startOffset); family += f } }
        }
        for (member in family) overrideFamily(member, name).forEach { (p, at) -> add(p, at) }
        val fileNamed = path.takeIf {
            decl is KtClassOrObject && decl.parent is KtFile && path.substringAfterLast('/').substringBeforeLast('.') == name
        }
        val topLevel = decl.parent is KtFile && scope == null
        return KotlinUsageTarget(
            name, kindOf(decl, scope != null), ids, decls, scope?.let { path to it }, importFqnsOf(decl, kt), fileNamed,
            javaFacade = if (topLevel && (decl is KtNamedFunction || decl is KtProperty)) facadeClassName(kt, path) else null,
            topLevelTypePackage = if (topLevel && decl is KtClassOrObject) kt.packageFqName.asString() else null,
        )
    }

    /**
     * The JVM class a file's top-level functions and properties compile into: its `@file:JvmName`, or the file
     * name capitalized with `Kt` added. Null when the file name is not a plain identifier (the compiler mangles
     * those, and a guess would wrongly skip Java files that do call it).
     */
    private fun facadeClassName(kt: KtFile, path: String): String? {
        JVM_NAME.find(kt.text)?.let { return it.groupValues[1] }
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        if (base.isEmpty() || !base[0].isJavaIdentifierStartCompat() || !base.all { it.isLetterOrDigit() || it == '_' }) return null
        return base.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }

    private fun Char.isJavaIdentifierStartCompat(): Boolean = isLetter() || this == '_'

    private fun kindOf(decl: KtNamedDeclaration, local: Boolean): String = when (decl) {
        is KtClassOrObject -> "class"
        is KtNamedFunction -> "function"
        is KtParameter -> "parameter"
        is KtProperty -> if (local && decl.parent is KtBlockExpression) "variable" else "property"
        else -> "symbol"
    }

    /**
     * Where references to [decl] can be: a parameter's function or lambda, a local's block, a private member's
     * class, a private top-level's file. Null when anywhere in the project.
     */
    private fun scopeOf(decl: KtNamedDeclaration, kt: KtFile): TextRange? {
        fun KtElement.range() = TextRange(textRange.startOffset, textRange.endOffset)
        if (decl is KtParameter) {
            val owner = decl.parent?.parent ?: decl.parent
            if (owner is KtPrimaryConstructor) {
                if (decl.valOrVarKeyword == null) return owner.getParentOfType<KtClassOrObject>()?.range()
                // A constructor `val`/`var` is a member: scoped like one.
            } else return (owner ?: decl).range()
        }
        var n: KtElement? = decl.parent
        while (n != null) {
            when (n) {
                is KtBlockExpression -> return n.range()
                is KtClassBody, is KtFile -> break
            }
            n = n.parent
        }
        if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            val owner = decl.getParentOfType<KtClassOrObject>(strict = true)
            return owner?.range() ?: kt.range()
        }
        return null
    }

    /**
     * For a member, the members of the same name it overrides and that override it, declared in project
     * Kotlin source: (file path, name offset) each. Starts from the topmost overridden member so siblings in
     * the hierarchy come along too. Empty for anything that is not an open, abstract or overriding member.
     */
    private fun overrideFamily(decl: KtNamedDeclaration, name: String): List<Pair<String, Int>> {
        if (decl !is KtNamedFunction && decl !is KtProperty && decl !is KtParameter) return emptyList()
        if (decl is KtParameter && decl.valOrVarKeyword == null) return emptyList()
        val owner = decl.getParentOfType<KtClassOrObject>(strict = true) ?: return emptyList()
        val ownerFqn = owner.fqName?.asString() ?: return emptyList()
        val inInterface = owner is KtClass && owner.isInterface()
        val overrides = decl.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        val inheritable = inInterface || overrides || decl.hasModifier(KtTokens.OPEN_KEYWORD) || decl.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        if (!inheritable) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        val sig = signatureOf(decl)
        fun sourceMemberOf(typeFqn: String): Pair<String, Int>? =
            memberDeclaredIn(typeFqn, name, sig)?.let { (file, at) -> file.path to at }
                ?: javaMemberDeclaredIn(typeFqn, name, sig)?.let { it.file.path to it.offset }
        val roots = LinkedHashSet<String>().apply { add(ownerFqn) }
        if (overrides) {
            for (st in service.supertypesOf(ownerFqn)) {
                val m = sourceMemberOf(st.qualifiedName) ?: continue
                out += m; roots += st.qualifiedName
            }
        }
        val seen = HashSet<String>(roots)
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty() && seen.size < 500) {
            val t = queue.removeFirst()
            for (sub in service.directInheritors(t)) {
                if (!seen.add(sub.fqn)) continue
                sourceMemberOf(sub.fqn)?.let { out += it }
                queue.addLast(sub.fqn)
            }
        }
        return out
    }

    /**
     * The member named [name] DECLARED in source type [typeFqn] (not one it inherits), as its file and name
     * offset. `declaringClassFqn` is only set for binary callables, so a source member is placed by the class its
     * declaration sits in.
     */
    private fun memberDeclaredIn(typeFqn: String, name: String, sig: MemberSignature? = null): Pair<VirtualFile, Int>? {
        val declared = service.membersNamed(typeFqn, emptyList(), name).mapNotNull { s ->
            if (!s.origin.fromSource) return@mapNotNull null
            val dn = s.declaration() as? KotlinDomNode ?: return@mapNotNull null
            val d = dn.psi as? KtNamedDeclaration ?: return@mapNotNull null
            if (d.getParentOfType<KtClassOrObject>(strict = true)?.fqName?.asString() != typeFqn) return@mapNotNull null
            d to dn.owner.file
        }.distinctBy { it.first.textRange.startOffset }
        // Of several overloads, the one with the same parameters: the same types, else types that differ only where
        // the base's mentions a type parameter (a generic `fun f(x: T)` is overridden as `f(x: String)`).
        val pick = if (sig == null) declared.firstOrNull() else {
            declared.firstOrNull { signatureOf(it.first)?.types == sig.types }
                ?: declared.firstOrNull { c -> signatureOf(c.first)?.let { sig.accepts(it) } == true }
        }
        return pick?.let { (d, file) -> d.nameIdentifier?.textRange?.startOffset?.let { file to it } }
    }

    /**
     * The member named [name] declared in the project's JAVA type [typeFqn] (a Java class extending a Kotlin
     * one), or null. Java's own lookup falls back to the class itself when the member is not there, which is
     * not an answer to this question, so only a method or field counts.
     */
    private fun javaMemberDeclaredIn(typeFqn: String, name: String, sig: MemberSignature? = null): NavTarget? {
        // Java sees Kotlin classes too (as light classes), so asked about a Kotlin class it would answer with an
        // overload [memberDeclaredIn] just rejected. Kotlin types are this side's to judge; only a type with no
        // Kotlin source is a Java one. A function's parameters pick the Java overload (a type written with a
        // type parameter fits anything).
        if (service.sourceFileDeclaringType(typeFqn) != null) return null
        val paramTypes = sig?.let { s -> s.types.map { t -> if (s.mentionsTypeParameter(t)) "" else t } }
        return javaMemberLocator(typeFqn, name, paramTypes)?.takeIf { it.kind == "method" || it.kind == "field" }
    }

    /**
     * A function's parameter types as normalized text (`""` where unknown), and the type parameter names in scope
     * at its declaration (its own and its classes'). Null for a property, which has no parameters to tell apart.
     */
    private class MemberSignature(val types: List<String>, val typeParameters: Set<String> = emptySet()) {
        val arity: Int get() = types.size

        /** Whether [other] (a subtype's member) can override this: the same arity, each parameter the same type,
         *  unknown on either side, or one this signature writes with a type parameter. */
        fun accepts(other: MemberSignature): Boolean {
            if (other.arity != arity) return false
            return types.indices.all { i ->
                val b = types[i]
                val c = other.types[i]
                b == c || b.isEmpty() || c.isEmpty() || mentionsTypeParameter(b)
            }
        }

        fun mentionsTypeParameter(type: String): Boolean =
            typeParameters.any { tp -> Regex("""\b${Regex.escape(tp)}\b""").containsMatchIn(type) }
    }

    private fun signatureOf(decl: KtNamedDeclaration): MemberSignature? {
        val fn = decl as? KtNamedFunction ?: return null
        val typeParameters = HashSet<String>()
        fn.typeParameters.mapNotNullTo(typeParameters) { it.name }
        generateSequence(fn.getParentOfType<KtClassOrObject>(strict = true)) { it.getParentOfType<KtClassOrObject>(strict = true) }
            .forEach { c -> (c as? KtClass)?.typeParameters?.mapNotNullTo(typeParameters) { it.name } }
        return MemberSignature(fn.valueParameters.map { normalizeType(it.typeReference?.text) }, typeParameters)
    }

    /** A type's text with the spaces and package qualifiers taken out, so `kotlin.String` reads as `String`. */
    private fun normalizeType(text: String?): String =
        (text ?: "").replace(Regex("""\s"""), "").replace(Regex("""\b(?:[a-z_]\w*\.)+(?=[A-Z])"""), "")

    /**
     * Go to Implementation on a MEMBER: the members of the same name declared in the subtypes of its class, at
     * every level, so `Shape.area` lists `Circle.area` and `Square.area` rather than the classes. A subtype in a
     * library that declares the member opens read-only. Null when the caret is not on a member (a type, a local,
     * a top-level function), so the caller lists the type's inheritors as before; empty when nothing overrides it.
     */
    private fun memberImplementationTargets(symbol: KotlinSymbol?, leaf: KtElement?): List<NavTarget>? {
        val (ownerFqn, name, sig) = memberAt(symbol, leaf) ?: return null
        val out = ArrayList<NavTarget>()
        val seen = hashSetOf(ownerFqn)
        val queue = ArrayDeque(listOf(ownerFqn))
        while (queue.isNotEmpty() && seen.size < 500) {
            for (sub in service.directInheritors(queue.removeFirst())) {
                if (!seen.add(sub.fqn)) continue
                queue.addLast(sub.fqn)
                val label = "$name  ·  ${sub.fqn.substringAfterLast('.')}"
                val source = memberDeclaredIn(sub.fqn, name, sig)
                if (source != null) {
                    out += NavTarget(source.first, source.second, label, "method")
                    continue
                }
                if (service.isClasspathType(sub.fqn)) {
                    val declared = service.membersNamed(sub.fqn, emptyList(), name)
                        .filter { it.declaringClassFqn == sub.fqn && (sig == null || it.paramTypes.size == sig.arity) }
                    if (declared.isNotEmpty()) out += NavTarget(LibraryFile(libraryPath(sub.fqn, name)), 0, label, "library")
                    continue
                }
                // A Java class in the project extending the Kotlin one.
                javaMemberDeclaredIn(sub.fqn, name, sig)?.let { out += it.copy(label = label) }
            }
        }
        return out.sortedBy { it.label }
    }

    /**
     * The member under the caret as (owning class FQN, name): the function or property whose NAME the caret is on
     * in a class, or a reference resolving to one. Null for anything else, an extension included (it overrides
     * nothing).
     */
    private fun memberAt(symbol: KotlinSymbol?, leaf: KtElement?): Triple<String, String, MemberSignature?>? {
        leaf ?: return null
        // On a declaration's own name: the symbol does not resolve there, the tree answers.
        val decl = generateSequence(leaf) { it.parent }.take(3).filterIsInstance<KtNamedDeclaration>().firstOrNull()
        if (decl != null && decl.nameIdentifier?.textRange?.let { leaf.textRange.startOffset in it.startOffset until it.endOffset } == true) {
            val isMember = decl is KtNamedFunction || decl is KtProperty || (decl is KtParameter && decl.valOrVarKeyword != null)
            if (!isMember || (decl as? KtCallableDeclaration)?.receiverTypeReference != null) return null
            val owner = decl.getParentOfType<KtClassOrObject>(strict = true)?.fqName?.asString() ?: return null
            return Triple(owner, decl.name ?: return null, signatureOf(decl))
        }
        symbol ?: return null
        if (symbol.kind != SymbolKind.METHOD && symbol.kind != SymbolKind.FIELD) return null
        if (symbol.isExtension) return null
        val sourceDecl = if (symbol.origin.fromSource) (symbol.declaration() as? KotlinDomNode)?.psi as? KtNamedDeclaration else null
        val owner = if (symbol.origin.fromSource) {
            sourceDecl?.getParentOfType<KtClassOrObject>(strict = true)?.fqName?.asString()
        } else symbol.declaringClassFqn
        val sig = sourceDecl?.let(::signatureOf)
            ?: if (symbol.kind == SymbolKind.METHOD) {
                // A library member: a type-parameter slot reads as unknown, which any override's type satisfies.
                MemberSignature(symbol.paramTypes.map { t -> if ((t as? KotlinType)?.isTypeParameter == true) "" else normalizeType(t?.qualifiedName) })
            } else null
        return owner?.let { Triple(it, symbol.name, sig) }
    }

    /** The FQNs an import can name [decl] by: a top-level declaration, a class, a member of an object. */
    private fun importFqnsOf(decl: KtNamedDeclaration, kt: KtFile): Set<String> {
        val name = decl.name ?: return emptySet()
        if (decl is KtClassOrObject) return setOfNotNull(decl.fqName?.asString())
        if (decl.parent is KtFile) {
            val pkg = kt.packageFqName.asString()
            return setOf(if (pkg.isEmpty()) name else "$pkg.$name")
        }
        val obj = (decl.parent as? KtClassBody)?.parent as? KtObjectDeclaration
        return setOfNotNull(obj?.fqName?.asString()?.let { "$it.$name" })
    }

    /** The innermost function enclosing [offset] (the suspend-override diagnostic anchors on its name). */
    private fun functionCovering(ktFile: KtFile, offset: Int): KtNamedFunction? {
        var n: KtElement? =
            ktFile.findElementAt(offset.coerceIn(0, (ktFile.textLength - 1).coerceAtLeast(0)))
        while (n != null) {
            if (n is KtNamedFunction) return n; n = n.parent
        }
        return null
    }

    /** The innermost class/object enclosing [offset] (the abstract-not-implemented diagnostic anchors on its name). */
    private fun classCovering(ktFile: KtFile, offset: Int): KtClassOrObject? {
        var n: KtElement? =
            ktFile.findElementAt(offset.coerceIn(0, (ktFile.textLength - 1).coerceAtLeast(0)))
        while (n != null) {
            if (n is KtClassOrObject) return n; n = n.parent
        }
        return null
    }

    /** The leading whitespace (indent) of the line containing [offset] in [text]. */
    private fun lineIndentOf(text: CharSequence, offset: Int): String {
        val lineStart =
            text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(lineStart, i).toString()
    }

    /** The `by`-delegated properties whose delegate expression covers [offset] — the targets a delegate-operator
     *  import fix applies to (the `kt.delegateOperator` diagnostic is anchored on the delegate expression). */
    private fun delegatePropertiesCovering(ktFile: KtFile, offset: Int): List<KtProperty> {
        val out = ArrayList<KtProperty>()
        fun rec(p: KtElement) {
            if (p is KtProperty) p.delegateExpression?.textRange?.let { if (offset >= it.startOffset && offset <= it.endOffset) out += p }
            var c = p.firstChild
            while (c != null) {
                rec(c); c = c.nextSibling
            }
        }
        rec(ktFile)
        return out
    }

    /** Quick documentation for the symbol at [offset]: a declaration the caret sits ON is documented directly
     *  (raw KDoc from its PSI); otherwise the reference under the caret is resolved to a symbol. */
    fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? {
        val ktFile = KotlinParserHost.parse(file.name, text)
        val off = offset.coerceIn(0, ktFile.textLength)
        val leaf = ktFile.findElementAt(off.coerceAtMost((ktFile.textLength - 1).coerceAtLeast(0)))
        val ownDecl = leaf?.parent as? KtNamedDeclaration
        if (ownDecl != null && ownDecl.nameIdentifier === leaf) return declarationDoc(ownDecl)
        val node = KotlinParsedFile(ktFile, file, 0L).nodeAt(off)
        val sym = (resolve(node) as? ResolveResult.Resolved)?.symbol as? KotlinSymbol ?: return null
        return symbolDoc(sym)
    }

    private fun symbolDoc(sym: KotlinSymbol): QuickDocInfo {
        val sig = sym.signature?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("(")) "${sym.name}$it" else it } ?: sym.name
        val container =
            sym.owner?.name ?: sym.packageName ?: sym.declaringClassFqn?.substringAfterLast('.')
                ?.takeIf { it.isNotEmpty() }
        val declPsi = (sym.declaration() as? KotlinDomNode)?.psi
        val rawKdoc = (declPsi as? KtDeclaration)?.docComment?.text?.takeIf { it.isNotBlank() }
        // A library symbol has no PSI: its doc comes from the attached sources, with its markup. A Java
        // library's Javadoc keeps its comment markers; a Kotlin library's KDoc comes from the index already
        // undecorated.
        val fromSources = if (rawKdoc == null) service.sourceDocOf(sym) else null
        val (doc, fmt) = when {
            rawKdoc != null -> rawKdoc to DocFormat.KDOC
            fromSources != null -> fromSources.text to when {
                !fromSources.raw -> DocFormat.PLAIN
                fromSources.text.startsWith("/*") -> DocFormat.JAVADOC
                else -> DocFormat.KDOC
            }
            else -> sym.documentation() to DocFormat.PLAIN
        }
        return QuickDocInfo(sig, sym.name, sym.kind, container, service.withApiLevel(sym, doc), fmt)
    }

    private fun declarationDoc(decl: KtNamedDeclaration): QuickDocInfo {
        val sig: String
        val kind: SymbolKind
        when (decl) {
            is KtNamedFunction -> {
                sig =
                    "fun ${decl.name}(${paramTypes(decl.valueParameters)})" + (decl.typeReference?.text?.let { ": $it" }
                        ?: "")
                kind = SymbolKind.METHOD
            }

            is KtProperty -> {
                sig =
                    "${if (decl.isVar) "var" else "val"} ${decl.name}" + (decl.typeReference?.text?.let { ": $it" }
                        ?: "")
                kind = SymbolKind.FIELD
            }

            is KtClass -> {
                sig = "${if (decl.isInterface()) "interface" else "class"} ${decl.name}"
                kind = if (decl.isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
            }

            else -> {
                sig = decl.name ?: ""; kind = SymbolKind.CLASS
            }
        }
        val container =
            generateSequence(decl.parent) { it.parent }.filterIsInstance<KtClassOrObject>()
                .firstOrNull()?.name
        val raw = decl.docComment?.text?.takeIf { it.isNotBlank() }
        return QuickDocInfo(
            sig, decl.name ?: "", kind, container, raw,
            if (raw != null) DocFormat.KDOC else DocFormat.PLAIN,
        )
    }

    fun resolve(node: DomNode): ResolveResult {
        val kdn = node as? KotlinDomNode ?: return ResolveResult.Unresolved
        refreshOverlay() // go-to-definition must reach a symbol just declared in another open file
        val parsed = kdn.owner
        syncFocal(parsed) // ...and one declared in the same buffer being edited
        return resolveWith(kdn, KotlinResolver(parsed.ktFile, parsed, service))
    }

    /**
     * [resolve] without the freshness hooks, over a [resolver] the caller owns. For a sweep across many files
     * (Find Usages, Rename): making each scanned file the focal one would rebuild the symbol model and drop
     * the source memos once per file, and would take the focus off the file actually being edited.
     */
    private fun resolveWith(kdn: KotlinDomNode, resolver: KotlinResolver): ResolveResult {
        val parsed = kdn.owner
        val psi = kdn.psi as? KtNameReferenceExpression ?: return ResolveResult.Unresolved
        val name = psi.getReferencedName()

        // What the dot is applied to. `x.foo` makes the NAME the selector, but `x.foo()` makes the CALL the
        // selector and the name merely its callee -- so looking only at the name's own parent missed every
        // qualified method call, which is the commonest go-to there is. `x.foo` resolved and `x.foo()` did
        // not, on every host, until this walked past the call.
        val callee = psi.parent as? KtCallExpression
        val selector = if (callee != null && callee.calleeExpression === psi) callee else psi
        val qualified = (selector.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === selector }

        /** In scope at the caret, which is where a top-level callable and an imported EXTENSION live. */
        fun inScope(): Symbol? =
            // The exact-name probe mode: a resolution already knows the name it wants, so the top-level lookup
            // is an exact index scan instead of materializing every top-level callable on the classpath (the
            // empty-prefix query is uncapped) only to filter it down to one name here.
            resolver.scopeSymbolsAt(psi.textRange.startOffset, namePrefix = name, exactName = true)
                .filter { it.name == name }
                // The scan answers same-named top-level callables from every package, so taking the first sent
                // a call to an imported `greet` to an unrelated `greet` elsewhere in the project. The one the
                // file can actually see wins.
                .minByOrNull { visibilityRank(it, parsed.ktFile) }

        val sym: Symbol? = if (qualified != null) {
            // A bare type-parameter receiver (`t.member` where `t: T`, `<T : Bound>`) navigates to the member of
            // the parameter's upper bound; a normal receiver is unchanged (see receiverForMembers).
            val receiverType = resolver.inferType(qualified.receiverExpression)
                ?.let { resolver.receiverForMembers(it, qualified.receiverExpression.textRange.startOffset) }
            val member = receiverType
                ?.let { recv -> service.membersNamed(recv.qualifiedName, recv.typeArguments, name).firstOrNull() }
            // A member wins over an extension of the same name, as it does at the call site; but an
            // extension IS reachable through a dot, so a miss falls through to the scope rather than
            // reporting the reference unresolved.
            member ?: receiverType?.let { staticMemberOn(it.qualifiedName, name) } ?: inScope()
        } else if (psi.getParentOfType<KtUserType>(strict = true) != null) {
            // The caret is inside a TYPE reference, so the name denotes a type even where something else in
            // scope answers to it. Asking the scope first resolved the `String` in `fun f(): String` to the
            // `String(chars)` FACTORY FUNCTION, and every hover and go-to followed it there.
            //
            // A type PARAMETER comes first among types: `class Box<T>` makes `T` mean the parameter inside
            // that class even where a class `T` exists, which is exactly what shadowing means.
            typeParameterInScope(psi, name) ?: typeNamed(name, resolver) ?: projectTypeAlias(name)
                ?: inScope()
        } else {
            namedArgumentParameter(psi, name, parsed, resolver) ?: inScope() ?: typeNamed(name, resolver)
        }
        // A CALL binds to one overload, which the name lookups above do not choose between (they take the first
        // same-named member). The call resolution the diagnostics use does, so navigation, Find Usages and Go to
        // Implementation land on the overload actually called.
        if (callee != null && sym is KotlinSymbol && sym.kind == SymbolKind.METHOD) {
            resolver.resolveCalleeFunction(callee)
                ?.takeIf { it.name == name && it.kind == SymbolKind.METHOD }
                ?.let { return ResolveResult.Resolved(it) }
        }
        return sym?.let { ResolveResult.Resolved(it) } ?: ResolveResult.Unresolved
    }

    /**
     * The TYPE that simple [name] denotes at the use site, for a reference nothing in scope claims as a value or
     * callable: a type reference (`StringBuilder`, an imported class) resolves to its classifier.
     *
     * Gated through [KotlinSymbolService.resolveTypeName], which honours the file's imports, its package and the
     * default star imports. It is deliberately NOT a search of the classpath by simple name: such a search picks
     * an arbitrary same-named class from anywhere on the classpath, so in a file importing
     * `androidx.compose.material3.Text` (a top-level function, whose own declaration lives in a facade class) the
     * name `Text` resolved to `android.jar`'s `org.w3c.dom.Text`, and every go-to navigation, hover and quick doc
     * followed it there.
     */
    /**
     * The type parameter [name] refers to, declared by an enclosing function, class or property.
     *
     * A type parameter is not in any scope the symbol model knows: it is not a classifier and not a value,
     * so every `T` in `class Box<T>(items: List<T>)` read as unresolved. The declaration is right there in
     * the tree, and walking out to it is what the semantic highlighter already does to colour these.
     *
     * The innermost owner wins, which is what shadowing means for `fun <T> …` inside `class C<T>`.
     */
    private fun typeParameterInScope(from: KtElement, name: String): KotlinSymbol? {
        var owner = from.getParentOfType<KtTypeParameterListOwner>(strict = true)
        while (owner != null) {
            if (owner.typeParameters.any { it.name == name }) {
                // No declaration node, deliberately. A `KtTypeParameter` is not represented in the neutral
                // DOM, so `nodeAt` walks past it to the FILE -- and a navigation target built from that
                // points at offset 0, which sends Ctrl-click to line 1. Resolving without a location is the
                // honest answer; giving it one means representing the declaration, not guessing a node.
                return KotlinSymbol(name = name, kind = SymbolKind.TYPE_PARAMETER)
            }
            owner = owner.getParentOfType<KtTypeParameterListOwner>(strict = true)
        }
        return null
    }

    /**
     * A `typealias` declared in project source, which the symbol model knows about but does not resolve.
     *
     * The model resolves CLASSES; an alias is a name for a type expression, so `typealias ShapeTable =
     * Map<String, Shape>` leaves `ShapeTable` resolving to nothing even though the unresolved-type
     * diagnostic already backs off on exactly these (see [KotlinSymbolService.isProjectTypeAlias]). Reported
     * as a CLASS: it is what the name denotes to a reader, and there is no alias kind in the neutral model.
     */
    private fun projectTypeAlias(name: String): KotlinSymbol? =
        if (service.isProjectTypeAlias(name)) KotlinSymbol(name, SymbolKind.CLASS) else null

    /**
     * The parameter a NAMED ARGUMENT names: the `prefix` in `joinToString(prefix = "[")`.
     *
     * Its label is a name reference like any other, so it was resolved against the scope — where there is of
     * course no `prefix` — and reported unresolved in the middle of a perfectly ordinary call. What it
     * actually refers to is a parameter of the callee, so the callee is resolved first and its parameter
     * names are consulted. Navigation does not follow (a library parameter has no declaration here), but the
     * reference stops reading as an error.
     */
    private fun namedArgumentParameter(
        psi: KtNameReferenceExpression, name: String, parsed: KotlinParsedFile, resolver: KotlinResolver,
    ): KotlinSymbol? {
        val argument = psi.getParentOfType<KtValueArgument>(strict = true) ?: return null
        if (argument.getArgumentName()?.text?.trim() != name) return null
        val call = argument.getParentOfType<KtCallExpression>(strict = true) ?: return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        // Resolve the callee the way any other reference is resolved, rather than asking the scope: the
        // commonest named-argument call is on a RECEIVER (`list.joinToString(prefix = "[")`), and its callee
        // is found through the receiver's members, which the scope knows nothing about.
        val calleeNode = parsed.nodeAt(callee.textRange.startOffset) as? KotlinDomNode ?: return null
        val target = (resolveWith(calleeNode, resolver) as? ResolveResult.Resolved)
            ?.symbol as? KotlinSymbol ?: return null
        // The callee must actually declare the name, or this is a typo and the diagnostic should say so.
        return if (name in target.paramNames) KotlinSymbol(name, SymbolKind.PARAMETER, owner = target) else null
    }

    /**
     * A member reached STATICALLY, through the type rather than through a value of it.
     *
     * `membersNamed` answers a type's INSTANCE members, and the three things a dot on a type name reaches
     * are none of them: an enum's constants (`Kind.UNKNOWN`), the members of its companion
     * (`Counter.zero()`), and its nested types (`Outer.Nested`). Completion has consulted all three since
     * it was written, which is why `Kind.` listed the entries while go-to-definition on the very same
     * offset reported them unresolved.
     *
     * Deliberately not gated on the receiver BEING a type: a value receiver simply has no enum constants
     * and no companion, so the extra lookups answer nothing and cost a miss each, and this only runs after
     * the instance-member lookup has already failed.
     */
    private fun staticMemberOn(typeFqn: String, name: String): KotlinSymbol? =
        service.enumConstantsOf(typeFqn).firstOrNull { it.name == name }
            ?: service.companionMembersFor(typeFqn, name).firstOrNull { it.name == name }
            ?: service.companionObjectSymbol(typeFqn)?.takeIf { it.name == name }
            ?: service.nestedTypesOf(typeFqn, name).firstOrNull { it.name == name }

    private fun typeNamed(name: String, resolver: KotlinResolver): KotlinSymbol? {
        val fqn = service.resolveTypeName(name, resolver.fileContext)
            ?.takeIf { service.isKnownType(it) } ?: return null
        // The index page carries the real classifier kind (class/interface/enum/annotation); a type it does not
        // hold (the page is capped) still resolves, as a plain class.
        return service.typeNamesByPrefix(name).firstOrNull { (it.type as? KotlinType)?.qualifiedName == fqn }
            ?: KotlinSymbol(name, SymbolKind.CLASS, type = service.typeByFqn(fqn))
    }
}

/** The assignment operators: a name on their left is written. */
private val ASSIGN_OPS = setOf(
    KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
)

/** `++` / `--`: their operand is written. */
private val INC_DEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

/** A file's `@file:JvmName("…")`, which names the class its top-level declarations compile into. */
private val JVM_NAME = Regex("""@file\s*:\s*JvmName\s*\(\s*"([^"]+)"\s*\)""")
