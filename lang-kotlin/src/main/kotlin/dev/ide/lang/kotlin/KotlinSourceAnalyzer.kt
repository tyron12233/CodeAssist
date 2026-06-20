package dev.ide.lang.kotlin

import dev.ide.index.IndexService
import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.kotlin.completion.KotlinCompletionService
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.ComposableContext
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import dev.ide.lang.kotlin.symbols.KotlinType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Paths

/**
 * The editor-time engine for Kotlin. Tolerant PSI parse to neutral DOM, plus resolution, the inference
 * subset, and member/name/type completion, all on an independent symbol model (the compiler is used only to
 * parse). Mirrors [dev.ide.lang.jdt.JdtSourceAnalyzer]: built from a model-derived [CompilationContext],
 * with [indexService] injected by the host after construction (it powers type-NAME completion via
 * `java.classNames`; members come from bytecode).
 */
class KotlinSourceAnalyzer(ctx: CompilationContext) : SourceAnalyzer, Disposable {

    /** Injected by the host (ide-core's `analyzerFor`). */
    @Volatile
    var indexService: IndexService? = null

    /** Injected by the host: where to persist the classpath extension scan across launches. */
    @Volatile
    var extensionCacheDir: Path? = null

    /** Injected by the host: synthetic ("light") classes this module should resolve (Android `R`/`BuildConfig`,
     *  ViewBinding, …). The host excludes the Kotlin `<File>Kt` facades (a Kotlin file uses its own top-level
     *  declarations directly). Queried lazily so a resource change is picked up without rebuilding the analyzer. */
    @Volatile
    var syntheticClassProvider: () -> List<dev.ide.lang.synthetic.SyntheticClass> = { emptyList() }

    /** Injected by the host: real parameter names + javadoc/KDoc from attached SOURCES (index-backed, with an
     *  on-demand parse fallback). Lets completing a Java/Android/library API from a `.kt` file show real names
     *  and docs instead of `p0`/`p1` and nothing. */
    @Volatile
    var sourceDocProvider: dev.ide.lang.resolve.SourceDocProvider = dev.ide.lang.resolve.SourceDocProvider.NONE

    /** Injected by the host: the current live editor buffers (VirtualFile path → text) for CROSS-file
     *  freshness — a declaration just typed in ANOTHER open file resolves/completes here before it is saved
     *  and reindexed. Pushed into the symbol model at each analyze/complete/resolve; the model diffs the
     *  buffers and reparses only what changed (a no-op when nothing did), so the refresh is cheap. */
    @Volatile
    var liveOverlayProvider: () -> Map<String, String> = { emptyMap() }

    /** Sync the symbol model to the current live buffers before a query (see [liveOverlayProvider]). */
    private fun refreshOverlay() {
        runCatching { service.setOverlay(liveOverlayProvider()) }
    }

    private val sourceRoots: List<VirtualFile> = ctx.sourceRoots
    private val classpathJars: List<Path> =
        (ctx.classpath.entries + ctx.bootClasspath.entries)
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
            .filter { Files.exists(it) }

    private val serviceLazy = lazy {
        KotlinSymbolService(sourceRoots, classpathJars, indexService, extensionCacheDir, { syntheticClassProvider() }, sourceDocProvider)
    }
    private val service: KotlinSymbolService get() = serviceLazy.value

    private val backing = KotlinIncrementalParser()
    private val lastByFile = ConcurrentHashMap<String, KotlinParsedFile>()

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            // A settled buffer is parseFull'd for several features in succession (analyze, semantic highlight,
            // breadcrumb, …). The PSI parse is pure for a given text, so reuse the last parse when the text is
            // unchanged instead of re-running the parser each time.
            lastByFile[snapshot.file.path]?.let { if (it.ktFile.text.contentEquals(snapshot.text)) return it }
            return (backing.parseFull(snapshot) as KotlinParsedFile).also { lastByFile[snapshot.file.path] = it }
        }

        override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult =
            backing.reparse(previous, newSnapshot, edits).also { lastByFile[newSnapshot.file.path] = it.tree as KotlinParsedFile }
    }

    override val completion: CompletionService by lazy { KotlinCompletionService(service) { refreshOverlay() } }

    override val inlayHints: dev.ide.lang.hints.InlayHintService by lazy {
        KotlinInlayHintService(
            parsedFor = { lastByFile[it.path] },
            resolverFor = { KotlinResolver(it.ktFile, it, service) },
        )
    }

    override val signatureHelp: dev.ide.lang.signature.SignatureHelpService by lazy {
        KotlinSignatureHelpService(service) { refreshOverlay() }
    }

    override val semanticHighlighter: dev.ide.lang.highlight.SemanticHighlightService by lazy {
        KotlinSemanticHighlighter(
            parsedFor = { lastByFile[it.path] },
            resolverFor = { KotlinResolver(it.ktFile, it, service) },
            refresh = { refreshOverlay() },
        )
    }

    override val folding: dev.ide.lang.folding.FoldingService by lazy {
        KotlinCodeFolder(parsedFor = { lastByFile[it.path] })
    }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        lastByFile[file.path] ?: incrementalParser.parseFull(EmptyDocument(file))

    // --- Compose preview (interpreter integration; see docs/compose-interpreter.md) ---

    /** The `@Preview @Composable` functions in [file]'s last parse — the editor's preview targets. */
    fun composePreviews(file: VirtualFile): List<dev.ide.lang.kotlin.interp.PreviewInfo> =
        lastByFile[file.path]?.let { dev.ide.lang.kotlin.interp.KotlinComposePreviews.find(it.ktFile) } ?: emptyList()

    /**
     * Whether [file]'s last parse contains syntax errors (`PsiErrorElement`s). A file that doesn't parse
     * cleanly must NOT be interpreted for a preview: the error-tolerant parser still yields a whole tree, but a
     * stray/incomplete token mis-shapes declarations — e.g. `data class Project(dsad val id: …)` parses into a
     * constructor whose parameters are all shifted, so the interpreter builds objects with wrong-typed fields
     * (a `Float` slot holding a `String`). That garbage program then crashes the real Compose runtime deep in
     * its measure/semantics pass (a `ClassCastException` Compose throws AFTER the interpreter returned), which
     * no interpreter-level guard can catch. So the preview gates on this and shows "fix errors" instead.
     */
    fun hasSyntaxErrors(file: VirtualFile): Boolean {
        val parsed = lastByFile[file.path] ?: return false
        return org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.hasErrorElements(parsed.ktFile)
    }

    /** Lower every top-level function in [file] to a [dev.ide.lang.kotlin.interp.ResolvedFunction], keyed
     *  `"name/arity"` — the program the interpreter runs a preview against (same-file composables included). */
    fun lowerFile(file: VirtualFile): Map<String, dev.ide.lang.kotlin.interp.ResolvedFunction> =
        loweredFor(file)?.program ?: emptyMap()

    /** Lower every source class/object/enum in [file] to a [dev.ide.lang.kotlin.interp.ResolvedClass] — the
     *  project-source types a preview's program may construct or reference (they aren't compiled at preview
     *  time, so the interpreter materializes them from this). Empty when [file] isn't parsed or lowering throws
     *  (the preview then hits the honest boundary for those types rather than losing the whole render). */
    fun lowerFileClasses(file: VirtualFile): List<dev.ide.lang.kotlin.interp.ResolvedClass> =
        loweredFor(file)?.classes ?: emptyList()

    // The lowered preview program + classes, cached per file. The preview re-renders on every keystroke AND
    // redundantly without a text change (the pane renders light + dark frames, detection runs alongside render,
    // and zoom/device switches re-fire) — PSI→ResolvedTree lowering (overload resolution against the classpath)
    // is the dominant interpreter-side cost, so it's memoized. Granularity is PER FUNCTION: a function's
    // lowering depends only on its own body + the file's *signatures* (a callee's params/return, a class header,
    // a top-level val's type), so editing one function's BODY (the hot case — typing inside a @Composable) only
    // re-lowers that function and reuses every sibling + the classes. `fileSigHash` is the file text with all
    // top-level function bodies stripped: it changes on any signature/import/class edit (→ re-lower everything,
    // conservative) but NOT on a body edit. A classpath change disposes the analyzer (host's invalidateAnalyzers),
    // dropping this with it. Cross-file source edits are best-effort (unchanged from the prior per-file cache).
    // textHash + startOffset: a function is reused only if its text AND its position are unchanged. The
    // offset guard keeps the lowered tree's SourceSpans valid — an edit that SHIFTS a sibling (e.g. typing in
    // an earlier function) moves its offset, so it re-lowers with fresh spans rather than serving stale ones.
    private class FnEntry(val textHash: Int, val startOffset: Int, val fn: dev.ide.lang.kotlin.interp.ResolvedFunction)
    private class Lowered(
        val fileSigHash: Int,
        val functions: Map<String, FnEntry>,
        val classes: List<dev.ide.lang.kotlin.interp.ResolvedClass>,
    ) {
        val program: Map<String, dev.ide.lang.kotlin.interp.ResolvedFunction> by lazy { functions.mapValues { it.value.fn } }
    }
    private val loweredCache = ConcurrentHashMap<String, Lowered>()

    /** The file text with every TOP-LEVEL function body elided — a hash of everything a sibling's lowering can
     *  depend on (signatures, imports, properties, class bodies). Stable across function-body edits. */
    private fun fileSignatureHash(ktFile: org.jetbrains.kotlin.psi.KtFile): Int {
        val text = ktFile.text
        val sb = StringBuilder(text.length)
        var pos = 0
        for (fn in ktFile.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()) {
            val body = fn.bodyExpression ?: continue
            val r = body.textRange
            if (r.startOffset >= pos) { sb.append(text, pos, r.startOffset); pos = r.endOffset }
        }
        sb.append(text, pos, text.length)
        return sb.toString().hashCode()
    }

    private fun loweredFor(file: VirtualFile): Lowered? {
        val parsed = lastByFile[file.path] ?: return null
        val sigHash = fileSignatureHash(parsed.ktFile)
        val prev = loweredCache[file.path]
        val sigMatch = prev != null && prev.fileSigHash == sigHash
        // Reuse the classes whole when no signature/class-body changed (only function bodies can change without
        // moving sigHash, and those never affect a class's lowering); else build the resolver lazily and re-lower.
        val resolver by lazy(LazyThreadSafetyMode.NONE) { dev.ide.lang.kotlin.interp.KotlinTreeResolver(parsed.ktFile, parsed, service) }
        val functions = parsed.ktFile.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>().associate { fn ->
            val ownHash = fn.text.hashCode()
            val start = fn.textRange.startOffset
            val key = "${fn.name}/${fn.valueParameters.size}"
            val reused = if (sigMatch) prev!!.functions[key]?.takeIf { it.textHash == ownHash && it.startOffset == start } else null
            key to (reused ?: FnEntry(ownHash, start, lowerOneFunction(resolver, fn)))
        }
        val classes = if (sigMatch) prev!!.classes else runCatching { resolver.lowerClasses() }.getOrDefault(emptyList())
        return Lowered(sigHash, functions, classes).also { loweredCache[file.path] = it }
    }

    private fun lowerOneFunction(
        resolver: dev.ide.lang.kotlin.interp.KotlinTreeResolver,
        fn: org.jetbrains.kotlin.psi.KtNamedFunction,
    ): dev.ide.lang.kotlin.interp.ResolvedFunction = try {
        resolver.lowerFunction(fn)
    } catch (t: Throwable) {
        // A resolver gap can THROW (an unhandled PSI shape, a null in inference) rather than produce an
        // Unsupported node — which would lose the whole file's lowering and leave the preview with no
        // reason. Turn it into a diagnostic so the cause is reported, not swallowed.
        val span = dev.ide.lang.kotlin.interp.SourceSpan(fn.textRange.startOffset, fn.textRange.endOffset)
        val reason = "lowering failed (${t::class.java.simpleName}): ${t.message ?: "no message"}"
        val params = fn.valueParameters.mapIndexed { i, p ->
            dev.ide.lang.kotlin.interp.RParam(dev.ide.lang.kotlin.interp.SlotId(i), p.name ?: "_", null)
        }
        dev.ide.lang.kotlin.interp.ResolvedFunction(
            fn.name ?: "?", params,
            dev.ide.lang.kotlin.interp.RNode.Unsupported(reason, fn.name ?: "", span),
            listOf(dev.ide.lang.kotlin.interp.LoweringDiagnostic(reason, span)),
        )
    }

    override suspend fun analyze(file: VirtualFile): AnalysisResult = KotlinPerf.trace("kt.analyze") {
        val parsed = lastByFile[file.path] ?: return@trace AnalysisResult(file, emptyList())
        KotlinPerf.span("overlay") { refreshOverlay() } // cross-file: a symbol just typed elsewhere must not flag here
        AnalysisResult(file, parsed.diagnostics + KotlinPerf.span("semantic") { semanticDiagnostics(parsed) })
    }

    /** A single "Import …" code action: its lightbulb [title] and the document [edits] that apply it. */
    class KotlinImportFix(val title: String, val edits: List<DocumentEdit>)

    /**
     * "Import …" quick-fixes for the unresolved references overlapping [offset] in [file]'s last parse — what
     * the editor lightbulb / Alt-Enter offers on an unimported `remember`, `mutableStateOf`, a type, etc. For
     * each `kt.unresolved` name under the caret, one fix per candidate fully-qualified name (top-level
     * callable / type), inserting `import <fqn>` after the existing imports (else the package directive, else
     * the file top). A candidate already imported contributes nothing; results are de-duplicated and capped.
     * A `kt.delegateOperator` diagnostic under the caret additionally offers imports for the delegate's missing
     * `getValue`/`setValue` operator (`import androidx.compose.runtime.getValue` for `by mutableStateOf`).
     */
    fun importFixesAt(file: VirtualFile, offset: Int): List<KotlinImportFix> {
        val parsed = lastByFile[file.path] ?: return emptyList()
        refreshOverlay()
        val text = parsed.ktFile.text
        val diags = semanticDiagnostics(parsed)
        fun coversCaret(d: Diagnostic) = offset >= d.range.start && offset <= d.range.end
        val unresolved = diags.filter { it.code == "kt.unresolved" && coversCaret(it) }
        val delegateOps = diags.filter { it.code == "kt.delegateOperator" && coversCaret(it) }
        if (unresolved.isEmpty() && delegateOps.isEmpty()) return emptyList()
        val insertOffset = importInsertOffset(parsed.ktFile)
        val existing = parsed.ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<KotlinImportFix>()
        fun offer(fqn: String) {
            if (fqn in existing || !seen.add(fqn)) return
            out += KotlinImportFix("Import $fqn", listOf(DocumentEdit(insertOffset, 0, "import $fqn\n")))
        }
        for (d in unresolved) {
            val name = text.substring(d.range.start.coerceIn(0, text.length), d.range.end.coerceIn(0, text.length))
            service.importCandidates(name).forEach(::offer)
        }
        if (delegateOps.isNotEmpty()) {
            val resolver = KotlinResolver(parsed.ktFile, parsed, service)
            for (prop in delegatePropertiesCovering(parsed.ktFile, offset)) {
                resolver.delegateOperatorImportCandidates(prop).forEach(::offer)
            }
        }
        return out.take(12)
    }

    /** The `by`-delegated properties whose delegate expression covers [offset] — the targets a delegate-operator
     *  import fix applies to (the `kt.delegateOperator` diagnostic is anchored on the delegate expression). */
    private fun delegatePropertiesCovering(ktFile: KtFile, offset: Int): List<KtProperty> {
        val out = ArrayList<KtProperty>()
        fun rec(p: PsiElement) {
            if (p is KtProperty) p.delegateExpression?.textRange?.let { if (offset >= it.startOffset && offset <= it.endOffset) out += p }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(ktFile)
        return out
    }

    /** Offset of a fresh line just after the last import (else the package directive, else the file start). */
    private fun importInsertOffset(ktFile: KtFile): Int {
        val text = ktFile.text
        val anchor = ktFile.importDirectives.maxOfOrNull { it.textRange.endOffset }
            ?: ktFile.packageDirective?.takeIf { it.text.isNotBlank() }?.textRange?.endOffset
            ?: return 0
        var i = anchor.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++  // to the end of the anchor's line
        if (i < text.length) i++                          // past its newline → start of a fresh line
        return i
    }

    /**
     * Semantic diagnostics. Conservative to avoid false positives over an incomplete (parse-only) symbol
     * model. It flags:
     *  - an unresolved member on an explicit receiver whose type was inferred (`"".bogus()`), including an
     *    extension that is on the classpath but not in scope (an unimported `16.dp`/`14.sp`);
     *  - an unresolved bare reference: a lower-case name in value position that resolves to nothing;
     *  - a named argument whose name matches no parameter of any function the call could resolve to;
     *  - a type mismatch for an initializer (`val a: Int = ""`) or a `return` value (`fun f(): Int { return "" }`);
     *  - a `val` reassignment (`val x = 1; x = 2`);
     *  - a missing `return` in a block-body function with a non-Unit declared return type;
     *  - conflicting declarations in one scope (duplicate names / same-signature functions);
     *  - an argument-count mismatch for a call to a same-file function (where arity is readable from PSI);
     *  - a missing initializer on a top-level / concrete-class property;
     *  - an unsafe nullable access (`s.length` where `s: String?`, no guard);
     *  - unused imports, `private` declarations, and locals, and a `var` that could be `val` (warnings/hints).
     * Capitalized names (types/constructors), type-position references (generic params), qualified receivers,
     * numeric-literal adaptation, `Nothing` terminals, smart-cast guards, and implicit-companion bodies are left alone.
     */
    // Per-file incremental-analyze cache. The semantic pass re-resolves the whole file, which is the editor's
    // dominant cost on a large file; this lets a body-only edit re-analyze ONLY the changed function and reuse
    // every other declaration's diagnostics (re-anchored to its shifted offset). See [semanticDiagnostics].
    private class DeclDiags(val header: String, val fullText: String, val rel: List<Diagnostic>)
    private class AnalyzeCache(val importsKey: String, val decls: List<DeclDiags>)
    private val analyzeCache = ConcurrentHashMap<String, AnalyzeCache>()

    /**
     * Semantic diagnostics, computed incrementally. The set is `file-level checks` + `Σ per top-level
     * declaration`. Walking the package/import subtrees yields nothing (the per-node checks back off inside
     * imports — see [unresolvedBareReference]), so this is equivalent to one whole-file walk.
     *
     * Incremental reuse: when exactly one top-level declaration changed AND it is a block-bodied function whose
     * HEADER (modifiers/annotations/receiver/params/return type) is unchanged, only its body changed — nothing
     * any OTHER declaration resolves against moved (a block body's return type is declared, so it's part of the
     * header), and imports are unchanged (guarded). So that one function is re-analyzed and every other
     * declaration's cached diagnostics are reused, re-anchored to its new start offset. Any other shape
     * (signature change, added/removed/reordered declaration, multiple changes, an import edit) → full re-analyze.
     */
    private fun semanticDiagnostics(parsed: KotlinParsedFile): List<Diagnostic> {
        val ktFile = parsed.ktFile
        val resolver = KotlinResolver(ktFile, parsed, service)
        val refNames = referencedNames(ktFile) // for unused-import / unused-private (names used in the body)
        val localAliases = typeAliasNamesIn(ktFile) // same-file typealiases (the disk model may lag the buffer)

        // File-level checks (whole-file, but cheap — no type resolution): always recomputed.
        val fileLevel = ArrayList<Diagnostic>()
        fileLevel += duplicateDeclarations(ktFile.declarations)
        fileLevel += unusedImports(ktFile, refNames)
        fileLevel += conflictingImports(ktFile)
        unusedPrivateDeclarations(ktFile, refNames, fileLevel)

        val topDecls = ktFile.declarations
        val importsKey = (ktFile.packageDirective?.text ?: "") + "\u0000" + (ktFile.importList?.text ?: "")
        val prev = analyzeCache[parsed.file.path]
        val recompute = KotlinPerf.span("scopeCheck") { recomputeIndices(prev, importsKey, topDecls) } // null → full

        val perDecl = ArrayList<List<Diagnostic>>(topDecls.size)
        val newEntries = ArrayList<DeclDiags>(topDecls.size)
        for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            if (recompute != null && i !in recompute) {
                val cached = prev!!.decls[i] // text identical → reuse, re-anchored to the (possibly shifted) offset
                perDecl += cached.rel.map { it.copy(range = TextRange(it.range.start + base, it.range.end + base)) }
                newEntries += cached
            } else {
                val diags = ArrayList<Diagnostic>()
                walkDecl(d, resolver, localAliases, diags)
                perDecl += diags
                newEntries += DeclDiags(headerOf(d), d.text, diags.map { it.copy(range = TextRange(it.range.start - base, it.range.end - base)) })
            }
        }
        analyzeCache[parsed.file.path] = AnalyzeCache(importsKey, newEntries)

        val result = ArrayList<Diagnostic>(fileLevel)
        perDecl.forEach { result += it }
        return result
    }

    /**
     * Which top-level declarations must be re-analyzed against [prev], or null to re-analyze the whole file.
     * Empty list → nothing changed (reuse all). One index → a safe body-only function edit (see
     * [semanticDiagnostics]). Anything else → null (full).
     */
    private fun recomputeIndices(prev: AnalyzeCache?, importsKey: String, topDecls: List<KtDeclaration>): List<Int>? {
        if (prev == null || prev.importsKey != importsKey || prev.decls.size != topDecls.size) return null
        val changed = ArrayList<Int>(2)
        for (i in topDecls.indices) if (topDecls[i].text != prev.decls[i].fullText) changed += i
        if (changed.size > 1) return null // a multi-declaration edit → don't reason about it; re-analyze fully
        if (changed.isEmpty()) return changed // identical text (e.g. a caret-only re-analyze) → reuse everything
        val k = changed[0]
        val fn = topDecls[k] as? KtNamedFunction ?: return null
        val body = fn.bodyBlockExpression ?: return null // expression bodies feed inference → treat as structural
        val newHeader = fn.text.substring(0, body.textRange.startOffset - fn.textRange.startOffset)
        return if (newHeader == prev.decls[k].header) changed else null // header changed → signature changed → full
    }

    /** A declaration's pre-body text (signature surface) for a block-bodied function; its full text otherwise. */
    private fun headerOf(d: KtDeclaration): String {
        val body = (d as? KtNamedFunction)?.bodyBlockExpression ?: return d.text
        return d.text.substring(0, body.textRange.startOffset - d.textRange.startOffset)
    }

    /** Walk one declaration's subtree, accumulating its semantic diagnostics into [out] (no file-level checks —
     *  those run once in [semanticDiagnostics]; a declaration subtree never contains the KtFile node). Only
     *  declaration-LOCAL checks live here; whole-file ones (unused-private, unused-import) are file-level so a
     *  body edit elsewhere can't leave a stale reused result. */
    private fun walkDecl(psi: PsiElement, resolver: KotlinResolver, localAliases: Set<String>, out: MutableList<Diagnostic>) {
        // Poll between nodes (never mid-I/O) so a higher-priority call — code completion sharing the one
        // engine thread — can preempt this pass instead of waiting it out. The host retries.
        dev.ide.platform.EngineCancellation.checkCanceled()
        when (psi) {
            is KtNameReferenceExpression -> KotlinPerf.span("sem.nameRef") {
                (unresolvedMember(psi, resolver) ?: unresolvedBareReference(psi, resolver))?.let { out += it }
            }
            is KtUserType -> KotlinPerf.span("sem.type") { unresolvedTypeReference(psi, resolver, localAliases)?.let { out += it } }
            is KtProperty -> {
                typeMismatch(psi.typeReference?.text, psi.initializer, resolver)?.let { out += it }
                unusedLocal(psi)?.let { out += it }
                missingInitializer(psi)?.let { out += it }
                varCouldBeVal(psi)?.let { out += it }
                delegateOperatorNotInScope(psi, resolver)?.let { out += it }
            }
            is KtParameter -> typeMismatch(psi.typeReference?.text, psi.defaultValue, resolver)?.let { out += it }
            is KtNamedFunction -> {
                // a block-body function must return a value (missing-return); an expression body is type-checked.
                if (psi.hasBlockBody()) missingReturn(psi, resolver)?.let { out += it }
                else typeMismatch(psi.typeReference?.text, psi.bodyExpression, resolver)?.let { out += it }
            }
            is KtReturnExpression -> returnTypeMismatch(psi, resolver)?.let { out += it }
            is KtBinaryExpression -> valReassignment(psi)?.let { out += it }
            is KtCallExpression -> KotlinPerf.span("sem.call") {
                // The same-file PSI check first (it also catches "too many arguments"); only fall back to the
                // overload-aware binary check when it didn't fire, so a call is never double-reported.
                KotlinPerf.span("call.argCount") {
                    val same = argumentCountMismatch(psi)
                    if (same != null) out += same
                    else missingRequiredArgument(psi, resolver)?.let { out += it }
                }
                KotlinPerf.span("call.ctor") { (constructorCallMismatch(psi, resolver) ?: sameFileConstructorMismatch(psi, resolver))?.let { out += it } }
                KotlinPerf.span("call.namedArgs") { out += unknownNamedArguments(psi, resolver) }
                KotlinPerf.span("call.composable") { composableInvocation(psi, resolver)?.let { out += it } }
                KotlinPerf.span("call.inferType") { cannotInferType(psi, resolver)?.let { out += it } }
            }
            is KtDotQualifiedExpression -> unsafeNullableAccess(psi, resolver)?.let { out += it }
            // Conflicting declarations within a scope below the file: a parameter list, a block (locals), or a
            // class body. (Top-level conflicts are a file-level check in semanticDiagnostics.)
            is KtParameterList -> out += duplicateParams(psi)
            is KtBlockExpression -> {
                out += duplicateDeclarations(psi.statements.filterIsInstance<KtDeclaration>())
                out += unreachableCode(psi, resolver)
            }
            is KtClassBody -> out += duplicateDeclarations(psi.declarations)
            else -> {}
        }
        var c = psi.firstChild
        while (c != null) { walkDecl(c, resolver, localAliases, out); c = c.nextSibling }
    }

    /** Unused `private` declarations — a whole-file check (its used-ness depends on references ANYWHERE in the
     *  file, so it can't be cached per declaration). Recurses the file flagging each private decl absent from
     *  [refNames]; locals can't carry a visibility modifier, so only top-level/member declarations match. */
    private fun unusedPrivateDeclarations(root: PsiElement, refNames: Set<String>, out: MutableList<Diagnostic>) {
        if (root is KtNamedDeclaration && (root is KtProperty || root is KtNamedFunction) && isPrivateDeclaration(root)) {
            unusedPrivate(root, refNames)?.let { out += it }
        }
        var c = root.firstChild
        while (c != null) { unusedPrivateDeclarations(c, refNames, out); c = c.nextSibling }
    }

    /** Every identifier referenced in the file body (outside import/package directives), for the
     *  unused-import and unused-private checks. A declaration's own name identifier is NOT a reference. */
    private fun referencedNames(file: KtFile): Set<String> {
        val names = HashSet<String>()
        fun rec(p: PsiElement) {
            if (p is KtImportDirective || p is KtPackageDirective) return
            // KtSimpleNameExpression covers plain references AND operation references (`a shl b` → `shl`),
            // so an infix function imported and used as an operator still counts as referenced.
            if (p is org.jetbrains.kotlin.psi.KtSimpleNameExpression) names += p.getReferencedName()
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(file)
        return names
    }

    /**
     * Same-scope redeclarations among [decls] — `val x = 1; val x = 2`, two properties of the same name, or
     * two functions with the same name AND signature (receiver + parameter types; overloads that differ in
     * parameter types are fine). Conservative: keys are textual (`Int` ≠ `kotlin.Int` won't merge), classes/
     * objects/enum entries and property-vs-function clashes are left alone. Every member of a clashing group
     * is flagged (matching the editor underlining each).
     */
    private fun duplicateDeclarations(decls: List<KtDeclaration>): List<Diagnostic> {
        if (decls.size < 2) return emptyList()
        val byKey = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        for (d in decls) {
            if (d !is KtNamedDeclaration || d.nameIdentifier == null) continue
            val name = d.name ?: continue
            val key = when (d) {
                is KtProperty -> "P:${d.receiverTypeReference?.text ?: ""}:$name"
                is KtNamedFunction ->
                    "F:${d.receiverTypeReference?.text ?: ""}:$name(${d.valueParameters.joinToString(",") { it.typeReference?.text ?: "?" }})"
                else -> continue // classes/objects/enum entries: not handled here
            }
            byKey.getOrPut(key) { ArrayList() }.add(d)
        }
        return conflicts(byKey.values)
    }

    /** Duplicate parameter names within one parameter list (`fun f(x: Int, x: String)`, `{ a, a -> }`). */
    private fun duplicateParams(list: KtParameterList): List<Diagnostic> {
        if (list.parameters.size < 2) return emptyList()
        val byName = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        for (p in list.parameters) {
            if (p.nameIdentifier == null) continue
            byName.getOrPut(p.name ?: continue) { ArrayList() }.add(p)
        }
        return conflicts(byName.values)
    }

    private fun conflicts(groups: Collection<List<KtNamedDeclaration>>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (group in groups) {
            if (group.size < 2) continue
            for (d in group) {
                val r = (d.nameIdentifier ?: d).textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Conflicting declarations: '${d.name}'", "kt.conflictingDeclaration",
                )
            }
        }
        return out
    }

    /**
     * `val x = …; x = …` — assigning to an immutable binding. Conservative: only a plain `=` to a SIMPLE name
     * (not `this.x =`, not `x += …` which may desugar to a legal `plusAssign` on a `val`) that resolves to a
     * nearby local `val` or a parameter (function/lambda/for/catch, all immutable). Resolution stops
     * at the enclosing class so a (possibly `var`) member never trips it.
     */
    private fun valReassignment(expr: KtBinaryExpression): Diagnostic? {
        if (expr.operationToken != KtTokens.EQ) return null
        val lhs = expr.left as? KtNameReferenceExpression ?: return null
        val decl = nearestLocalDecl(lhs.getReferencedName(), lhs.textRange.startOffset, lhs) ?: return null
        val immutable = when (decl) {
            // A `val` WITHOUT an initializer permits one deferred assignment (`val x: Int; x = 1`), so only a
            // `val` that already has a value is truly un-reassignable. (Double deferred-assignment isn't caught.)
            is KtProperty -> !decl.isVar && (decl.hasInitializer() || decl.hasDelegate())
            is KtParameter -> true
            else -> false
        }
        if (!immutable) return null
        val r = lhs.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Val cannot be reassigned", "kt.valReassign")
    }

    /** The nearest in-scope local property / parameter named [name] declared before [offset], or null if the
     *  binding is a class member / top-level (resolution backs off at the class boundary). */
    private fun nearestLocalDecl(name: String, offset: Int, from: PsiElement): PsiElement? {
        var node: PsiElement? = from.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression ->
                    node.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }?.let { return it }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtForExpression -> node.loopParameter?.takeIf { it.name == name }?.let { return it }
                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return it }
                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    /**
     * A block-body function whose declared return type is non-Unit but whose body never returns a value.
     * This does not do full control-flow analysis: it only flags when there is no `return` at all (a return
     * inside a lambda counts, conservatively) and the body doesn't end in a value-less terminal: a `throw`,
     * an infinite `while (true)`, or a `Nothing`-typed call (`TODO()`/`error(…)`).
     */
    private fun missingReturn(fn: KtNamedFunction, resolver: KotlinResolver): Diagnostic? {
        val declared = service.typeFromText(fn.typeReference?.text ?: return null, resolver.fileContext) ?: return null
        if (declared.qualifiedName == "kotlin.Unit" || declared.qualifiedName == "kotlin.Nothing") return null
        if (declared.isTypeParameter || !service.isKnownType(declared.qualifiedName)) return null
        val body = fn.bodyBlockExpression ?: return null
        if (containsReturn(body)) return null
        when (val last = body.statements.lastOrNull()) {
            is KtThrowExpression -> return null
            is KtWhileExpression -> if (last.condition?.text?.trim() == "true") return null
            is KtDoWhileExpression -> if (last.condition?.text?.trim() == "true") return null
            is KtExpression -> if (isNothingTerminal(last, resolver)) return null
            else -> {}
        }
        val anchor = fn.nameIdentifier ?: fn.typeReference ?: return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "A 'return' expression required in a function with a block body ('{...}')", "kt.missingReturn",
        )
    }

    /** A terminal that never returns normally: an inferred `Nothing`, or a call to a well-known `Nothing`
     *  stdlib function (`TODO()`/`error(…)`/`fail(…)`) which the parse-only inference may not type. */
    private fun isNothingTerminal(expr: KtExpression, resolver: KotlinResolver): Boolean {
        if (resolver.inferType(expr)?.qualifiedName == "kotlin.Nothing") return true
        val callee = (expr as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.getReferencedName() in setOf("TODO", "error", "fail")
    }

    /** Whether [scope] contains a `return` that targets it (returns inside a NESTED named function don't). */
    private fun containsReturn(scope: PsiElement): Boolean {
        var found = false
        fun rec(p: PsiElement) {
            if (found || (p is KtNamedFunction)) return // a nested fn owns its own returns
            if (p is KtReturnExpression) { found = true; return }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        var c = scope.firstChild
        while (c != null && !found) { rec(c); c = c.nextSibling }
        return found
    }

    /** A LOCAL `val`/`var` never referenced anywhere in its declaring block (a warning). Skips `_` and
     *  destructuring (modelled separately); counts any same-name reference as a use (so it never over-reports). */
    private fun unusedLocal(prop: KtProperty): Diagnostic? {
        val name = prop.name ?: return null
        if (name == "_") return null
        val block = prop.parent as? KtBlockExpression ?: return null // local declarations only
        val nameId = prop.nameIdentifier ?: return null
        var uses = 0
        fun rec(p: PsiElement) {
            if (p is KtNameReferenceExpression && p.getReferencedName() == name) uses++
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(block)
        if (uses > 0) return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Variable '$name' is never used", "kt.unusedLocal")
    }

    /**
     * A property that Kotlin requires to be initialized but isn't — a top-level or concrete-class property with
     * no initializer, delegate, or getter (`class C { val x: Int }`). Skips locals (deferred init is legal),
     * `abstract`/`lateinit`/`expect`/`external`, and members of an interface/abstract/expect class.
     */
    private fun missingInitializer(prop: KtProperty): Diagnostic? {
        if (prop.hasInitializer() || prop.hasDelegate() || prop.getter != null || prop.setter != null) return null
        if (prop.typeReference == null) return null // no type AND no initializer won't parse as a property
        if (prop.hasModifier(KtTokens.ABSTRACT_KEYWORD) || prop.hasModifier(KtTokens.LATEINIT_KEYWORD) ||
            prop.hasModifier(KtTokens.EXPECT_KEYWORD) || prop.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        ) return null
        val owner = prop.parent
        when (owner) {
            is KtFile -> {}
            is KtClassBody -> {
                val cls = owner.parent as? KtClassOrObject ?: return null
                if (cls is org.jetbrains.kotlin.psi.KtClass &&
                    (cls.isInterface() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                        cls.hasModifier(KtTokens.SEALED_KEYWORD) || cls.hasModifier(KtTokens.EXPECT_KEYWORD) ||
                        cls.hasModifier(KtTokens.EXTERNAL_KEYWORD))
                ) return null
            }
            else -> return null // local / other: deferred init is legal
        }
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Property must be initialized", "kt.mustBeInitialized")
    }

    /** A LOCAL `var` never reassigned could be a `val` (a hint). Reassignment = `name = …`, an augmented
     *  assignment, or `++`/`--` anywhere in the declaring block. */
    private fun varCouldBeVal(prop: KtProperty): Diagnostic? {
        if (!prop.isVar) return null
        val name = prop.name ?: return null
        val block = prop.parent as? KtBlockExpression ?: return null // locals only
        if (isReassignedIn(block, name)) return null
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.HINT, "Variable '$name' is never reassigned and can be a 'val'", "kt.varCouldBeVal")
    }

    private fun isReassignedIn(scope: PsiElement, name: String): Boolean {
        var found = false
        fun isTarget(e: KtExpression?) = (e as? KtNameReferenceExpression)?.getReferencedName() == name
        fun rec(p: PsiElement) {
            if (found) return
            when (p) {
                is KtBinaryExpression -> if (p.operationToken in ASSIGN_OPS && isTarget(p.left)) { found = true; return }
                is KtPrefixExpression -> if (p.operationToken in INCDEC && isTarget(p.baseExpression)) { found = true; return }
                is KtPostfixExpression -> if (p.operationToken in INCDEC && isTarget(p.baseExpression)) { found = true; return }
            }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        rec(scope)
        return found
    }

    private fun isPrivateDeclaration(d: KtNamedDeclaration): Boolean =
        d.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            !d.hasModifier(KtTokens.OPERATOR_KEYWORD) && !d.hasModifier(KtTokens.OVERRIDE_KEYWORD)

    /** A `private` top-level / member function or property never referenced in the file (a warning). `private`
     *  is file- or class-scoped, so a whole-file reference scan is sound. */
    private fun unusedPrivate(decl: KtNamedDeclaration, refNames: Set<String>): Diagnostic? {
        // only top-level or class-body members (not a local, not a primary-constructor parameter)
        if (decl.parent !is KtFile && decl.parent !is KtClassBody) return null
        val name = decl.name ?: return null
        if (name in refNames) return null
        val kind = if (decl is KtNamedFunction) "Function" else "Property"
        val nameId = decl.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "$kind '$name' is never used", "kt.unusedPrivate")
    }

    /** An import whose name is never referenced in the body (a warning). Skips star imports and imports of
     *  operator/convention names (used implicitly via `+`, `[]`, `in`, destructuring, which aren't visible here). */
    private fun unusedImports(file: KtFile, refNames: Set<String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (imp in file.importDirectives) {
            if (imp.isAllUnder) continue // star import: can't tell
            val fq = imp.importedFqName ?: continue
            val name = imp.aliasName ?: fq.shortName().asString()
            if (name.isEmpty() || name in OPERATOR_NAMES || name in refNames) continue
            val r = (imp.importedReference ?: imp).textRange
            out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Unused import directive", "kt.unusedImport")
        }
        return out
    }

    /**
     * Two or more explicit imports that bring the SAME simple name (or alias) into scope but resolve to
     * DIFFERENT fully-qualified targets — Kotlin's `CONFLICTING_IMPORT` (`import java.util.Date` +
     * `import java.sql.Date`: a bare `Date` is then ambiguous, and the file does not compile until one is
     * aliased or removed). Every member of a conflicting group is flagged (matching the editor underlining
     * each). Conservative — purely textual on the import list, so it never resolves anything:
     *  - star imports are skipped (they don't conflict at import time; use-site ambiguity is a separate matter);
     *  - an ALIAS changes the effective name, so `import java.sql.Date as SqlDate` no longer collides;
     *  - identical duplicate imports (same target) are NOT a conflict (a single distinct target), they're
     *    merely redundant — left to the unused/duplicate handling, not flagged as ambiguous.
     */
    private fun conflictingImports(file: KtFile): List<Diagnostic> {
        val byName = LinkedHashMap<String, MutableList<KtImportDirective>>()
        for (imp in file.importDirectives) {
            if (imp.isAllUnder) continue // star import: no name brought in at import time
            val fq = imp.importedFqName ?: continue
            val name = imp.aliasName ?: fq.shortName().asString()
            if (name.isEmpty()) continue
            byName.getOrPut(name) { ArrayList() }.add(imp)
        }
        val out = ArrayList<Diagnostic>()
        for ((name, imports) in byName) {
            // Only a genuine ambiguity — two DIFFERENT targets sharing one name — conflicts.
            if (imports.mapNotNullTo(HashSet()) { it.importedFqName?.asString() }.size < 2) continue
            for (imp in imports) {
                val r = (imp.importedReference ?: imp).textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Conflicting import, imported name '$name' is ambiguous", "kt.conflictingImport",
                )
            }
        }
        return out
    }

    /**
     * Statements in [block] that follow a statement which never completes normally: a bare `return`/`throw`/
     * `break`/`continue` or a `Nothing`-typed terminal (`TODO()`). Only DIRECT block statements count as
     * terminators (an `if (c) return` doesn't end the block), so this stays false-positive-free. Reported as
     * one warning spanning the dead range.
     */
    private fun unreachableCode(block: KtBlockExpression, resolver: KotlinResolver): List<Diagnostic> {
        val stmts = block.statements
        val cut = stmts.indexOfFirst { it is KtReturnExpression || it is KtThrowExpression || it is KtBreakExpression ||
            it is KtContinueExpression || (it is KtExpression && isNothingTerminal(it, resolver)) }
        if (cut < 0 || cut == stmts.lastIndex) return emptyList()
        val first = stmts[cut + 1]
        val last = stmts.last()
        return listOf(Diagnostic(TextRange(first.textRange.startOffset, last.textRange.endOffset), Severity.WARNING, "Unreachable code", "kt.unreachable"))
    }

    /**
     * An argument-count mismatch for a call to a function declared in THIS file, the only place the exact
     * arity is readable from the PSI (default values, varargs, trailing lambda), so it's safe. A unique
     * same-file candidate by name (skipped if overloaded or has a vararg) whose required/maximum arity the
     * call violates is flagged.
     */
    /**
     * A call (member, top-level, or constructor — source OR binary) that omits a REQUIRED argument: `Button { }`
     * without `onClick`. Delegates to [KotlinResolver.missingRequiredArgument], which is sound across overloads
     * and backs off whenever per-parameter defaults aren't known. Reported as `kt.argumentCount` (the same code
     * as the same-file check), so quick-fixes keyed on it apply uniformly. The span is the argument list, or the
     * callee name for a bare trailing-lambda call (`Button { }`, which has no `(...)`).
     */
    private fun missingRequiredArgument(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val missing = runCatching { resolver.missingRequiredArgument(call) }.getOrNull() ?: return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val r = call.valueArgumentList?.textRange ?: callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "No value passed for required parameter $missing of '${callee.getReferencedName()}'", "kt.argumentCount",
        )
    }

    private fun argumentCountMismatch(call: KtCallExpression): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null // member call: receiver unknown
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null // named/spread: arity rules differ
        val name = callee.getReferencedName()
        val candidates = call.containingKtFile.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.name == name && it.receiverTypeReference == null }
        val fn = candidates.singleOrNull() ?: return null // overloaded or not same-file → don't guess
        val params = fn.valueParameters
        if (params.any { it.isVarArg }) return null
        val required = params.count { !it.hasDefaultValue() && !it.isVarArg }
        val max = params.size
        val n = call.valueArguments.size
        if (n in required..max) return null
        val r = call.valueArgumentList?.textRange ?: callee.textRange
        val msg = if (n > max) "Too many arguments for '$name' (expected ${if (required == max) "$max" else "$required..$max"})"
        else "No value passed for ${required - n} required argument(s) of '$name'"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, "kt.argumentCount")
    }

    /**
     * A constructor call (`TextView(this)`, `Foo(1, 2)`) whose arguments don't fit any of the type's
     * constructors. Conservative — designed to never false-positive over the parse-only model:
     *  - only a capitalized callee that resolves to a KNOWN type whose constructors are enumerable;
     *  - **count**: flagged only when NO constructor has the argument count AND none could be variadic
     *    (an array/vararg param makes the arity open); skipped entirely for binary Kotlin classes (their
     *    metadata doesn't surface default arguments, so a same-arity check would be unsound);
     *  - **type**: an argument whose inferred type is not assignable to the parameter type of the unique
     *    arity-matching constructor (same [isMismatch] rule as a declaration's `val a: T = …` — both types
     *    must be fully-known concrete types, so an unmodeled/partial hierarchy backs off instead of
     *    false-flagging). Named/spread arguments are skipped.
     */
    private fun constructorCallMismatch(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
        // The type name: a bare `Foo(…)`, or a qualified `pkg.Foo(…)`/`Outer.Inner(…)` where the call is the
        // selector (the receiver text + the callee name). `constructorTypeFqn` rejects it unless it resolves to
        // a known type, so a real member call (`list.add(x)`, `obj.method()`) is left to other checks.
        val parent = call.parent
        val name = if (parent is KtQualifiedExpression && parent.selectorExpression === call) {
            parent.receiverExpression.text + "." + callee.getReferencedName()
        } else {
            callee.getReferencedName()
        }
        val fqn = resolver.constructorTypeFqn(name, callee.textRange.startOffset) ?: return null
        if (service.hasKotlinMetadata(fqn)) return null // binary Kotlin: default args not visible → don't guess
        val ctors = service.constructorsOf(fqn)
        if (ctors.isEmpty()) return null
        val n = call.valueArguments.size
        // A vararg/array parameter (the display keeps `[]`) makes the arity open — don't flag the count.
        val variadic = ctors.any { it.signature?.contains("[]") == true }
        if (!variadic && ctors.none { it.paramTypes.size == n }) {
            val arities = ctors.map { it.paramTypes.size }.toSortedSet().joinToString("/")
            val r = call.valueArgumentList?.textRange ?: callee.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "No constructor of '$name' takes $n argument(s) (expected $arities)", "kt.constructorArgs",
            )
        }
        // Per-argument type check against the unique arity-matching constructor.
        val match = ctors.singleOrNull { it.paramTypes.size == n } ?: return null
        for ((i, arg) in call.valueArguments.withIndex()) {
            val expr = arg.getArgumentExpression() ?: continue
            val pt = match.paramTypes.getOrNull(i) as? KotlinType ?: continue
            val at = resolver.inferType(expr) ?: continue
            if (isMismatch(pt, at)) {
                val r = expr.textRange
                return mismatchDiagnostic(r.startOffset, r.endOffset, at, pt)
            }
        }
        return null
    }

    /**
     * The same check as [constructorCallMismatch] but for a class declared in THIS file, where the PSI gives
     * exact arity (default values + varargs) — the binary path can't (the typeShape index/decode has no source
     * class, and binary metadata hides defaults). A bare `Foo(args)` whose callee is a same-file top-level
     * `KtClass`: its argument count must fit some constructor's required..max, and each argument's inferred
     * type must be assignable to the corresponding parameter of the unique arity-fitting constructor (the
     * [isMismatch] rule). Backs off on overloads it can't reason about: a companion object (a possible
     * `invoke` operator), any variadic constructor, and non-instantiable kinds.
     */
    private fun sameFileConstructorMismatch(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
        val name = callee.getReferencedName()
        if (name.firstOrNull()?.isUpperCase() != true) return null
        val cls = call.containingKtFile.declarations.filterIsInstance<KtClass>().firstOrNull { it.name == name } ?: return null
        if (cls.isInterface() || cls.isAnnotation() || cls.isEnum() || cls.companionObjects.isNotEmpty()) return null
        val ctors = constructorParameterLists(cls)
        if (ctors.any { params -> params.any { it.isVarArg } }) return null // open arity → don't guess
        val n = call.valueArguments.size
        if (ctors.none { n in it.count { p -> !p.hasDefaultValue() }..it.size }) {
            val arities = ctors.flatMap { it.count { p -> !p.hasDefaultValue() }..it.size }.toSortedSet().joinToString("/")
            val r = call.valueArgumentList?.textRange ?: callee.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "No constructor of '$name' takes $n argument(s) (expected $arities)", "kt.constructorArgs",
            )
        }
        // Type-check against the unique constructor whose arity fits.
        val match = ctors.singleOrNull { n in it.count { p -> !p.hasDefaultValue() }..it.size } ?: return null
        for ((i, arg) in call.valueArguments.withIndex()) {
            val expr = arg.getArgumentExpression() ?: continue
            val pt = service.typeFromText(match.getOrNull(i)?.typeReference?.text, resolver.fileContext) ?: continue
            val at = resolver.inferType(expr) ?: continue
            if (isMismatch(pt, at)) {
                val r = expr.textRange
                return mismatchDiagnostic(r.startOffset, r.endOffset, at, pt)
            }
        }
        return null
    }

    /** A same-file class's constructor parameter lists: the primary (or an implicit no-arg when there are no
     *  explicit constructors at all) plus every secondary constructor. */
    private fun constructorParameterLists(cls: KtClass): List<List<KtParameter>> {
        val lists = ArrayList<List<KtParameter>>()
        cls.primaryConstructor?.let { lists.add(it.valueParameters) }
        cls.secondaryConstructors.forEach { c: KtSecondaryConstructor -> lists.add(c.valueParameters) }
        if (lists.isEmpty()) lists.add(emptyList()) // no explicit constructor → implicit no-arg
        return lists
    }

    /**
     * `recv.member` on a nullable receiver without `?.`/`!!` (`val s: String? = …; s.length`). Conservative:
     * smart-casts are not modeled, so if the receiver is a simple name with any null-guard in the enclosing
     * function (`s != null`, `s ?:`, `s?.`, `s!!`), this backs off entirely to avoid the common false positive.
     */
    private fun unsafeNullableAccess(expr: KtDotQualifiedExpression, resolver: KotlinResolver): Diagnostic? {
        val receiver = expr.receiverExpression
        val recvType = resolver.inferType(receiver) ?: return null
        if (!recvType.nullable) return null
        // A simple-name receiver may be smart-cast after an untracked guard; skip if any guard exists.
        if (receiver is KtNameReferenceExpression && mayBeNullChecked(expr, receiver.getReferencedName())) return null
        val r = receiver.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Only safe (?.) or non-null asserted (!!) calls are allowed on a nullable receiver", "kt.unsafeNullable",
        )
    }

    /** Heuristic: does the enclosing function reference [name] alongside a null-related token that could
     *  smart-cast it to non-null, implying an unmodeled guard? Keeps [unsafeNullableAccess] from firing on
     *  `if (s != null) s.foo()` / `s!!.foo(); s.bar()` / `s ?: return; s.foo()`. A SAFE call (`s?.…`) is NOT
     *  a smart-cast — `val a = s?.x; s.y` leaves `s` nullable at `s.y` — so `?.` does not count as a guard. */
    private fun mayBeNullChecked(from: PsiElement, name: String): Boolean {
        val fn = from.getStrictParentOfType<KtNamedFunction>() ?: from.containingFile
        val text = fn.text ?: return true
        return Regex("\\b${Regex.escape(name)}\\b\\s*(!!|\\?:|[!=]=\\s*null)").containsMatchIn(text) ||
            Regex("null\\s*[!=]=\\s*\\b${Regex.escape(name)}\\b").containsMatchIn(text)
    }

    /**
     * A declared-type vs. initializer type mismatch (`val a: Int = ""`). Conservative to avoid false
     * positives over the parse-only model: flags only when BOTH the declared type and the inferred initializer
     * type are fully-known concrete types (no type parameters, no unknown names) and the value is not
     * assignable to the declaration. Numeric/numeric pairs are skipped entirely (integer literals adapt to
     * the expected numeric type, e.g. `val a: Long = 5`, which is not modeled here) and `Nothing`
     * (`TODO()`/`throw`) is assignable to everything, so it's skipped too.
     */
    private val NUMERIC = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
    )

    private val ASSIGN_OPS = setOf(
        KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
    )
    private val INCDEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

    // Convention/operator function names: imported for use via `+`, `[]`, `in`, `by`, destructuring, etc.,
    // which don't surface the name as a textual reference — so an unused-import check must not flag them.
    private val OPERATOR_NAMES = setOf(
        "plus", "minus", "times", "div", "rem", "mod", "plusAssign", "minusAssign", "timesAssign", "divAssign",
        "remAssign", "inc", "dec", "unaryPlus", "unaryMinus", "not", "get", "set", "invoke", "contains",
        "iterator", "next", "hasNext", "compareTo", "equals", "rangeTo", "rangeUntil", "provideDelegate",
        "getValue", "setValue", "component1", "component2", "component3", "component4", "component5",
    )

    private fun typeMismatch(declaredText: String?, init: KtExpression?, resolver: KotlinResolver): Diagnostic? {
        if (declaredText == null || init == null) return null
        val declared = service.typeFromText(declaredText, resolver.fileContext)
        val actual = resolver.inferType(init)
        if (!isMismatch(declared, actual)) return null
        val r = init.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** `return <expr>` whose value type isn't assignable to the enclosing block-body function's declared
     *  return type (`fun f(): Int { return "" }`). Skips labeled returns (they target a lambda). */
    private fun returnTypeMismatch(ret: KtReturnExpression, resolver: KotlinResolver): Diagnostic? {
        if (ret.getTargetLabel() != null) return null
        val value = ret.returnedExpression ?: return null
        val fn = ret.getStrictParentOfType<KtNamedFunction>() ?: return null
        if (!fn.hasBlockBody()) return null
        val declared = service.typeFromText(fn.typeReference?.text ?: return null, resolver.fileContext)
        if (declared?.qualifiedName == "kotlin.Unit") return null
        val actual = resolver.inferType(value)
        if (!isMismatch(declared, actual)) return null
        val r = value.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** Whether [actual] is a confidently-incompatible value for a declaration of type [declared].
     *  Both must be fully-known concrete types; numeric/numeric and `Nothing` are excused (see [typeMismatch]). */
    private fun isMismatch(declared: KotlinType?, actual: KotlinType?): Boolean {
        if (declared == null || actual == null) return false
        if (declared.isTypeParameter || actual.isTypeParameter) return false
        if (actual.qualifiedName == "kotlin.Nothing") return false
        if (!service.isKnownType(declared.qualifiedName) || !service.isKnownType(actual.qualifiedName)) return false
        if (declared.isAssignableFrom(actual)) return false
        return !(declared.qualifiedName in NUMERIC && actual.qualifiedName in NUMERIC)
    }

    private fun mismatchDiagnostic(start: Int, end: Int, actual: KotlinType, declared: KotlinType) = Diagnostic(
        TextRange(start, end), Severity.ERROR,
        "Type mismatch: inferred type is ${renderType(actual)} but ${renderType(declared)} was expected",
        "kt.typeMismatch",
    )

    private fun renderType(t: KotlinType): String {
        val simple = t.qualifiedName.substringAfterLast('.')
        val args = if (t.typeArguments.isEmpty()) ""
        else t.typeArguments.joinToString(", ", "<", ">") { it.qualifiedName.substringAfterLast('.') }
        return simple + args + if (t.nullable) "?" else ""
    }

    /**
     * A bare reference (no explicit receiver) in value position whose name resolves to nothing: a likely typo
     * or missing import, whether it's a call (`prinltn("x")`), a constructor / composable call (`Foo()`,
     * `Greeting()`), an assignment target, or a plain read (`val x = bogus` / a bare `ComponentActivity`).
     * Both lower- and upper-case names are checked (a capitalized name resolves via a type/object/import, a
     * same-file class, or a constructor — [KotlinResolver.bareNameResolves] knows all of these). Skipped for:
     * member selectors (handled by [unresolvedMember]), the receiver of a qualified expression (could be a
     * package or a type's static access), type-position references (handled by [unresolvedTypeReference]) and
     * annotations, import/package directives, named-argument labels, the implicit lambda `it`, a property
     * accessor's `field`, and any scope with a companion object (whose members are bare-accessible but not modeled).
     */
    private fun unresolvedBareReference(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `a.b` selector, or the callee `b` of `a.b()` — a member, resolved by unresolvedMember (not here).
        if (parent is KtQualifiedExpression && parent.selectorExpression === expr) return null
        if (parent is KtCallExpression && parent.calleeExpression === expr) {
            (parent.parent as? KtQualifiedExpression)?.let { if (it.selectorExpression === parent) return null }
        }
        // `x.foo` / `x.foo()` — the receiver `x` could be a package or a type (static access); back off.
        if (parent is KtQualifiedExpression && parent.receiverExpression === expr) return null
        if (parent is KtValueArgumentName) return null // a named-argument label, not a reference
        // `this`/`super` parse as a KtNameReferenceExpression under a KtInstanceExpressionWithLabel — they are
        // keywords (a receiver, typed by the resolver), never an unresolved name.
        if (parent is org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel) return null
        if (inImportOrPackage(expr) || inTypeReference(expr)) return null
        val name = expr.getReferencedName()
        if (name.isEmpty()) return null
        if (name == "it" && hasAncestor(expr) { it is org.jetbrains.kotlin.psi.KtLambdaExpression }) return null
        if (name == "field" && hasAncestor(expr) { it is KtPropertyAccessor }) return null
        val off = expr.textRange.startOffset
        if (resolver.companionInScope(off) || resolver.bareNameResolves(name, off)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", "kt.unresolved")
    }

    /**
     * A TYPE reference whose simple name resolves to nothing in scope — a missing import or a typo
     * (`val a: ComponentActivity` / `class X : AnyClass()` with no matching import). The outermost SIMPLE
     * (unqualified) user type is checked. Conservative back-offs that are NOT errors: an explicitly-imported
     * name, a generic type parameter in scope, a project `typealias`, and annotation type references (their own
     * rules). Qualified / nested names (`java.util.Locale`, `Outer.Inner`) are left to other checks.
     */
    private fun unresolvedTypeReference(userType: KtUserType, resolver: KotlinResolver, localAliases: Set<String>): Diagnostic? {
        // A qualified type (`java.util.Locale`, `Outer.Inner`) or a qualifier SEGMENT of one (the `gen` in
        // `gen.Txt`) — left alone; only a standalone simple name is checked.
        if (userType.qualifier != null || userType.parent is KtUserType) return null
        // Annotation type references (`@Composable`) have their own resolution rules — not flagged here.
        if (hasAncestor(userType) { it is org.jetbrains.kotlin.psi.KtAnnotationEntry }) return null
        val ref = userType.referenceExpression ?: return null
        val name = ref.getReferencedName()
        if (name.isEmpty()) return null
        val ctx = resolver.fileContext
        if (ctx.imports.any { !it.isStar && it.simpleName == name }) return null // explicitly imported → trust it
        if (name in localAliases || service.isProjectTypeAlias(name)) return null // a typealias, not a class
        val off = userType.textRange.startOffset
        if (resolver.isTypeParameterInScope(name, off)) return null
        // In scope (imported / same-package / source / default / builtin / star-imported) → resolves; don't flag.
        val resolved = service.resolveTypeName(name, ctx)
        if (resolved != null && service.isKnownType(resolved)) return null
        val r = ref.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", "kt.unresolved")
    }

    /** Simple names of every `typealias` declared in [file] (the live buffer the disk model may lag). */
    private fun typeAliasNamesIn(file: KtFile): Set<String> {
        val out = HashSet<String>()
        fun rec(p: PsiElement) {
            if (p is org.jetbrains.kotlin.psi.KtTypeAlias) p.name?.let { out += it }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(file)
        return out
    }

    /** True if [expr] sits inside a type reference (so a lower-case generic type param isn't flagged). */
    private fun inTypeReference(expr: PsiElement): Boolean {
        var p: PsiElement? = expr.parent
        while (p != null && p !is KtDeclaration) { if (p is KtTypeReference) return true; p = p.parent }
        return false
    }

    private fun inImportOrPackage(expr: PsiElement): Boolean =
        hasAncestor(expr) { it is KtImportDirective || it is KtPackageDirective }

    private inline fun hasAncestor(expr: PsiElement, predicate: (PsiElement) -> Boolean): Boolean {
        var p: PsiElement? = expr.parent
        while (p != null) { if (predicate(p)) return true; p = p.parent }
        return false
    }

    private fun unresolvedMember(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `recv.name` (property) or `recv.name(...)` (call — expr is the callee under the selector call).
        val receiver = when {
            parent is KtQualifiedExpression && parent.selectorExpression === expr -> parent.receiverExpression
            parent is KtCallExpression && parent.calleeExpression === expr ->
                (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
            else -> null
        } ?: return null
        val recvType = resolver.inferType(receiver) ?: return null // unknown receiver → don't flag
        // A type-parameter receiver (an un-inferred `T`) can't have its concrete members enumerated — the
        // universal `T`-receiver stdlib extensions (`let`/`run`/`apply`/…) would make the set non-empty and
        // falsely "resolve" everything-or-nothing. Back off rather than flag a member we can't verify.
        if (recvType.isTypeParameter) return null
        val name = expr.getReferencedName()
        if (name.isEmpty()) return null // an incomplete `recv.` — not a real member reference (and avoids a full scan)
        // `Owner.Nested` — a nested type/object reached through a member selector (Compose's `Icons.Filled`,
        // `Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`): a classifier, not an instance member, so it never
        // appears in membersOf. Probe the candidate nested FQN (mirrors KotlinResolver.nestedType) so a chain
        // bottoming out at an icon extension property (`Icons.AutoMirrored.Filled.List`) isn't falsely flagged.
        if (name.isNotEmpty() && service.isKnownType("${recvType.qualifiedName}.$name")) return null
        // `super.member` (e.g. `super.onCreate(...)`): a SOURCE supertype's members are fully enumerable, but a
        // binary/framework supertype (`ComponentActivity`) reaches inherited members through boot-classpath
        // ancestors (`android.app.Activity`) the symbol reader may not have read — its chain enumeration is
        // best-effort, so don't risk a false "unresolved" on a valid override call.
        if (unwrapParen(receiver) is KtSuperExpression && service.sourceClass(recvType.qualifiedName) == null) return null
        // Does the receiver have a member named `name`? Push the NAME into the lookup so only same-named
        // members/extensions are materialized + receiver-bound — not the type's whole extension set (the
        // `kotlin.Any` bucket alone is thousands on a Compose classpath). A `Type.member` reference (`Color.Red`)
        // also sees the type's companion-object members/statics, which instance membersNamed doesn't list.
        val matching = service.membersNamed(recvType.qualifiedName, recvType.typeArguments, name) +
            if (resolver.isTypeReceiver(receiver)) service.companionMembersFor(recvType.qualifiedName, name).filter { it.name == name } else emptyList()
        if (matching.isNotEmpty()) {
            // A plain member resolves outright. An EXTENSION resolves only when it is actually in scope —
            // imported, same-package, or default-imported — so an unimported `16.dp` / `14.sp` (the extension is
            // on the classpath but not brought in) stays unresolved, as Kotlin reports.
            val ctx = resolver.fileContext
            if (matching.any { !it.isExtension || extensionInScope(it, ctx) }) return null
        }
        // No same-named member. Flag ONLY if the receiver type is actually enumerable — an unknown type (which
        // membersNamed can't see into) must not yield a false "unresolved" (the old `membersOf(…)` returned an
        // empty set for an unknown type and was skipped; `isKnownType` is that same guard without enumerating).
        if (!service.isKnownType(recvType.qualifiedName)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", "kt.unresolved")
    }

    /**
     * Whether an extension [sym] is in scope at the use site. Kotlin resolves an extension only when it is
     * imported (explicitly or via a star/default import) or declared in the file's own package, so a
     * classpath extension that was never imported (`16.dp` without `import androidx.compose.ui.unit.dp`) does
     * NOT resolve. No package info → don't guess a rejection (treat as in scope). Mirrors the interpreter's
     * `KotlinTreeResolver.extensionInScope`.
     */
    private fun extensionInScope(sym: KotlinSymbol, ctx: FileContext): Boolean {
        val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
        if (pkg == ctx.packageName || DefaultImports.isDefaultImported(pkg)) return true
        return ctx.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
    }

    /**
     * A named argument (`foo(bar = 1)`) whose name matches no parameter of any function/constructor the call
     * could resolve to — a typo like `Text(colour = …)`. Conservative to avoid false positives over the
     * parse-only model: it backs off entirely when the target is uncertain — a member call whose receiver
     * can't be typed, a callee that resolves to nothing, or any plausible target whose parameter names were
     * stripped (Java bytecode surfaces `p0`/`p1`, so a name can't be validated). A genuinely zero-parameter
     * target contributes no names but doesn't suppress the check. A name is flagged only when it definitely
     * belongs to no candidate overload.
     */
    private fun unknownNamedArguments(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        val named = call.valueArguments.mapNotNull { it.getArgumentName() }
        if (named.isEmpty()) return emptyList()
        // A member call we can't type → the target is unknown; don't risk a false positive.
        val parent = call.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === call &&
            resolver.inferType(parent.receiverExpression) == null
        ) return emptyList()
        val targets = resolver.callTargets(call)
        if (targets.isEmpty()) return emptyList()
        // A target whose parameter NAMES are unavailable (count mismatch / synthetic / blank) makes the check
        // unsound: the actually-resolved overload's names may be unknowable. Skip the whole call then.
        if (targets.any { t ->
                t.paramTypes.isNotEmpty() &&
                    (t.paramNames.size != t.paramTypes.size || t.paramNames.any { it.isEmpty() || isSyntheticParamName(it) })
            }
        ) return emptyList()
        val known = targets.flatMapTo(HashSet()) { it.paramNames.filter { n -> n.isNotEmpty() } }
        return named.mapNotNull { argName ->
            val id = argName.asName?.identifier ?: return@mapNotNull null
            if (id in known) return@mapNotNull null
            val ref = (argName as? KtValueArgumentName)?.referenceExpression ?: return@mapNotNull null
            val r = ref.textRange
            Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Cannot find a parameter with this name: $id", "kt.namedArgument")
        }
    }

    /** ASM surfaces stripped Java parameters as `p0`, `p1`, … — useless as named arguments and not validatable. */
    private fun isSyntheticParamName(n: String): Boolean = n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

    /**
     * A call to a `@Composable` function from a non-`@Composable` context — Compose's calling-convention error
     * (the compiler's `COMPOSABLE_INVOCATION`: "@Composable invocations can only happen from the context of a
     * @Composable function"). Conservative: it fires only when the callee is confidently `@Composable` AND the
     * surrounding context is confidently non-composable. An unknown lambda context (the parse-only model can't
     * resolve the enclosing call/expected type) backs off, so this never false-positives. Inline lambdas
     * (`repeat`/`with`/`forEach`) are transparent — a composable call inside one within a composable scope is fine.
     */
    private fun composableInvocation(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        // The error fires only when a @Composable callee is invoked from a NON_COMPOSABLE context, so check the
        // context FIRST: it's a cheap ancestor walk (the @Composable test is syntactic), whereas resolving the
        // callee is overload resolution against scope + classpath. In a real Compose UI file most calls sit in a
        // @Composable function/lambda, so this skips the expensive callee resolution for the vast majority.
        if (resolver.composableContextAt(call.textRange.startOffset) != ComposableContext.NON_COMPOSABLE) return null
        val callee = resolver.calleeFunctionOf(call) ?: return null
        if (!callee.isComposable) return null // a non-composable call from a plain context → nothing to flag
        val anchor = call.calleeExpression ?: call
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "@Composable invocation can only happen from the context of a @Composable function",
            "kt.composableInvocation",
        )
    }

    /**
     * A generic call whose type arguments can't be inferred — Kotlin's "Not enough information to infer type
     * variable T" (`val text by remember { mutableStateOf() }`: the inner `mutableStateOf()` has no argument to
     * pin `T`, no explicit type argument, and no expected type, so its result type is undetermined). The check
     * lives in [KotlinResolver.uninferableTypeParameters] (shared with the interpreter) and is conservative —
     * it reports a parameter only when nothing at the site could have inferred it.
     */
    private fun cannotInferType(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val uninferable = resolver.uninferableTypeParameters(call)
        if (uninferable.isEmpty()) return null
        val anchor = call.calleeExpression ?: call
        val r = anchor.textRange
        val vars = uninferable.joinToString(", ")
        val msg = if (uninferable.size == 1) "Not enough information to infer type variable $vars"
        else "Not enough information to infer type variables $vars"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, "kt.cannotInferType")
    }

    /**
     * A `by`-delegated property whose delegate-accessor operator isn't in scope — Kotlin desugars `val x by d`
     * to `d.getValue(thisRef, prop)` (and a `var` write to `d.setValue(…)`), so the operator must be callable.
     * For Compose's `MutableState` it is an EXTENSION in `androidx.compose.runtime`: `val text by remember {
     * mutableStateOf(0) }` does not compile without `import androidx.compose.runtime.getValue`. The detection
     * lives in [KotlinResolver.missingDelegateOperators] (shared with the interpreter) and is conservative —
     * an unmodeled operator never flags, so it only fires when the operator exists on the classpath but isn't
     * brought into scope.
     */
    private fun delegateOperatorNotInScope(prop: KtProperty, resolver: KotlinResolver): Diagnostic? {
        val missing = resolver.missingDelegateOperators(prop)
        if (missing.isEmpty()) return null
        val anchor = prop.delegateExpression ?: return null
        val r = anchor.textRange
        val ops = missing.joinToString("' and '")
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This delegate has no '$ops' operator in scope; import the delegate's '$ops' extension to use it",
            "kt.delegateOperator",
        )
    }

    /** Strip enclosing parentheses (`(super).foo` → `super`) so a receiver is classified by its real expression. */
    private fun unwrapParen(expr: KtExpression): KtExpression {
        var e: KtExpression = expr
        while (e is KtParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    // --- resolution / inference ---

    override fun resolve(node: DomNode): ResolveResult {
        val kdn = node as? KotlinDomNode ?: return ResolveResult.Unresolved
        refreshOverlay() // go-to-definition must reach a symbol just declared in another open file
        val parsed = kdn.owner
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
        val psi = kdn.psi as? KtNameReferenceExpression ?: return ResolveResult.Unresolved
        val name = psi.getReferencedName()
        val q = psi.parent as? KtQualifiedExpression
        val sym: Symbol? = if (q != null && q.selectorExpression === psi) {
            resolver.inferType(q.receiverExpression)?.let { recv ->
                service.membersNamed(recv.qualifiedName, recv.typeArguments, name).firstOrNull()
            }
        } else {
            resolver.scopeSymbolsAt(psi.textRange.startOffset).firstOrNull { it.name == name }
                ?: service.typeNamesByPrefix(name).firstOrNull { it.name == name }
        }
        return sym?.let { ResolveResult.Resolved(it) } ?: ResolveResult.Unresolved
    }

    override fun scopeAt(file: VirtualFile, offset: Int): Scope {
        val parsed = lastByFile[file.path] ?: return EmptyScope
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
        return KotlinScope(offset, resolver)
    }

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? {
        val parsed = lastByFile[file.path] ?: return null
        return KotlinResolver(parsed.ktFile, parsed, service).expectedTypeAt(offset)
    }

    override fun resolveType(node: DomNode): TypeRef? {
        val kdn = node as? KotlinDomNode ?: return null
        val expr = kdn.psi as? KtExpression ?: return null
        return KotlinResolver(kdn.owner.ktFile, kdn.owner, service).inferType(expr)
    }

    private inner class KotlinScope(private val offset: Int, private val resolver: KotlinResolver) : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> {
            val all = resolver.scopeSymbolsAt(offset)
            return if (filter.kinds == null) all else all.filter { it.kind in filter.kinds!! }
        }
        override fun resolve(name: String): ResolveResult =
            resolver.scopeSymbolsAt(offset).firstOrNull { it.name == name }
                ?.let { ResolveResult.Resolved(it) } ?: ResolveResult.Unresolved
    }

    /** Release the symbol service's open jar handles (mirrors JdtSourceAnalyzer's lifecycle tie-in). */
    override fun dispose() {
        if (serviceLazy.isInitialized()) service.close()
    }

    private class EmptyDocument(override val file: VirtualFile) : DocumentSnapshot {
        override val version: Long = 0
        override val text: CharSequence = ""
        override fun length(): Int = 0
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }
}
