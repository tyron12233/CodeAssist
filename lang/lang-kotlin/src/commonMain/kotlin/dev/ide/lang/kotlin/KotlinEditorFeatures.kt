package dev.ide.lang.kotlin

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
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
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
        NavKind.IMPLEMENTATION -> implementationTargets(typeRefFqn ?: contextTypeFqn(symbol, leaf))
        NavKind.SUPER -> superTargets(symbol, leaf)
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
        // A classpath symbol: navigate to the declaring type (a member opens its owner, caret on the member).
        if (!symbol.origin.fromSource) {
            val isType = symbol.kind == SymbolKind.CLASS || symbol.kind == SymbolKind.INTERFACE
            val owner = if (isType) symbol.type?.qualifiedName else (symbol.declaringClassFqn ?: symbol.type?.qualifiedName)
            val member = if (isType) null else symbol.name
            owner?.let { sourceOrLibrary(it, member)?.let { t -> return listOf(t) } }
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
        val (doc, fmt) = if (rawKdoc != null) rawKdoc to DocFormat.KDOC
        else sym.documentation() to DocFormat.PLAIN
        return QuickDocInfo(sig, sym.name, sym.kind, container, doc, fmt)
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
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
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
                .firstOrNull { it.name == name }

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
            namedArgumentParameter(psi, name, parsed) ?: inScope() ?: typeNamed(name, resolver)
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
    private fun namedArgumentParameter(psi: KtNameReferenceExpression, name: String, parsed: KotlinParsedFile): KotlinSymbol? {
        val argument = psi.getParentOfType<KtValueArgument>(strict = true) ?: return null
        if (argument.getArgumentName()?.text?.trim() != name) return null
        val call = argument.getParentOfType<KtCallExpression>(strict = true) ?: return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        // Resolve the callee the way any other reference is resolved, rather than asking the scope: the
        // commonest named-argument call is on a RECEIVER (`list.joinToString(prefix = "[")`), and its callee
        // is found through the receiver's members, which the scope knows nothing about.
        val target = (resolve(parsed.nodeAt(callee.textRange.startOffset)) as? ResolveResult.Resolved)
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
