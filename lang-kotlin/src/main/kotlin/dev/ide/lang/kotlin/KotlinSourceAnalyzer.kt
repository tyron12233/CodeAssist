package dev.ide.lang.kotlin

import dev.ide.index.IndexService
import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.completion.KotlinCompletionItems
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import com.intellij.psi.PsiElement
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.PreviewDeclProvider
import dev.ide.lang.kotlin.interp.PreviewInfo
import dev.ide.lang.kotlin.interp.PreviewModel
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.SourceDocProvider
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.synthetic.SyntheticClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
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
    var syntheticClassProvider: () -> List<SyntheticClass> = { emptyList() }

    /** Injected by the host: real parameter names + javadoc/KDoc from attached SOURCES (index-backed, with an
     *  on-demand parse fallback). Lets completing a Java/Android/library API from a `.kt` file show real names
     *  and docs instead of `p0`/`p1` and nothing. */
    @Volatile
    var sourceDocProvider: SourceDocProvider = SourceDocProvider.NONE

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

    /** Register the file being queried as the model's focal source from its live PSI, so a class/member declared
     *  in the buffer being edited resolves immediately — even before it's saved and reindexed (same-file
     *  freshness). Keyed by text hash in the service, so a repeated query on unchanged text is a no-op. */
    private fun syncFocal(parsed: KotlinParsedFile) {
        runCatching {
            service.syncFocal(parsed.file.path, parsed.ktFile.text.hashCode()) {
                dev.ide.lang.kotlin.symbols.SourceIndexBuilder.extractFrom(
                    parsed.ktFile,
                    parsed,
                    parsed.file.path
                )
            }
        }
    }

    private val sourceRoots: List<VirtualFile> = ctx.sourceRoots
    private val classpathJars: List<Path> =
        (ctx.classpath.entries + ctx.bootClasspath.entries)
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
            .filter { Files.exists(it) }

    private val serviceLazy = lazy {
        KotlinSymbolService(
            sourceRoots,
            classpathJars,
            indexService,
            extensionCacheDir,
            { syntheticClassProvider() },
            sourceDocProvider
        )
    }
    private val service: KotlinSymbolService get() = serviceLazy.value

    /** Whether the classpath symbol model can resolve library symbols yet. False during "dumb mode" (the
     *  workspace index is still building on first launch), the window in which library callables resolve to
     *  0 candidates. Mirrors the gate the editor's diagnostics/completion already honor; the Compose preview
     *  uses it to tell a still-warming classpath from a genuinely unsupported construct. */
    fun classpathReady(): Boolean = runCatching { service.classpathReady() }.getOrDefault(true)

    /** Whether the Compose runtime is actually on this module's classpath: a top-level `mutableStateOf` from
     *  `androidx.compose.runtime` resolves. Lets the preview distinguish "the `androidx.compose.*` AARs are
     *  still attaching" (the Learn scratch's one-time first-run download; show Preparing and retry) from a
     *  real failure. Defaults to true on any error so it never blocks a working preview. */
    fun composeRuntimeAttached(): Boolean = runCatching {
        service.topLevelByName("mutableStateOf").any {
            (it.packageName ?: it.declaringClassFqn.orEmpty()).startsWith("androidx.compose.runtime")
        }
    }.getOrDefault(true)

    private val backing = KotlinIncrementalParser()
    private val lastByFile = ConcurrentHashMap<String, KotlinParsedFile>()

    // A keystroke resolves the same snapshot twice — the diagnostics pass (incrementalAnalysis) AND, when the
    // preview is open, the Compose preview lowerer — each through its own KotlinResolver, recomputing inference
    // + overload resolution from cold (measured: ~1100 inferType + 654 callTargets duplicated per keystroke).
    // The lowerer reuses the diagnostics pass's per-snapshot memo caches instead. Keyed by file path; the prior
    // entry is unreachable once replaced. Safe: every engine lane runs on EngineScheduler's single serialized
    // worker, so the shared caches are never touched concurrently, and each pass keeps its own transient
    // resolver state (narrowings/reentrancy) — only the pure memo caches are shared.
    private class CachesEntry(val parsed: KotlinParsedFile, val caches: KotlinResolverCaches)

    private val cachesBySnapshot = ConcurrentHashMap<String, CachesEntry>()

    /** Diagnostics ALWAYS resolves with a fresh cache: a dependency edit leaves the dependent file's OWN
     *  snapshot unchanged, so reusing a prior cache would stale-serve cross-file results (caught by
     *  `crossFileDependencyEditInvalidatesDependentCache`). The fresh cache is published so the preview lowerer
     *  of the SAME keystroke can reuse it ([reuseCachesFor]). */
    private fun freshCachesFor(parsed: KotlinParsedFile): KotlinResolverCaches {
        val c = KotlinResolverCaches()
        cachesBySnapshot[parsed.file.path] = CachesEntry(parsed, c)
        return c
    }

    /** The preview lowerer reuses the cache the diagnostics pass just published for this EXACT snapshot (the
     *  per-keystroke win — no redundant second resolution); absent one (a preview with no diagnostics pass), it
     *  builds and publishes its own. Preview is best-effort and re-renders on change, so reuse is safe here. */
    private fun reuseCachesFor(parsed: KotlinParsedFile): KotlinResolverCaches {
        cachesBySnapshot[parsed.file.path]?.let { if (it.parsed === parsed) return it.caches }
        return freshCachesFor(parsed)
    }

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            // A settled buffer is parseFull'd for several features in succession (analyze, semantic highlight,
            // breadcrumb, …). The PSI parse is pure for a given text, so reuse the last parse when the text is
            // unchanged instead of re-running the parser each time.
            val prev = lastByFile[snapshot.file.path]
            if (prev != null && prev.ktFile.text.contentEquals(snapshot.text)) return prev
            // Text changed: reparse only the edited subtree of the prior PSI in place (reusing the rest), which
            // is far cheaper than re-lexing/re-parsing the whole file on a large buffer. Falls back to a full
            // parse when incremental reparse doesn't apply / fails (a failed reparse leaves the prior tree
            // possibly mutated, so it's discarded here by replacing the map entry with the fresh parse).
            if (prev != null) {
                KotlinParserHost.tryReparse(prev.ktFile, snapshot.text)?.let { reparsed ->
                    return KotlinParsedFile(reparsed, snapshot.file, snapshot.version)
                        .also { lastByFile[snapshot.file.path] = it }
                }
            }
            return (backing.parseFull(snapshot) as KotlinParsedFile).also {
                lastByFile[snapshot.file.path] = it
            }
        }

        override fun reparse(
            previous: ParsedFile,
            newSnapshot: DocumentSnapshot,
            edits: List<DocumentEdit>
        ): ReparseResult =
            backing.reparse(previous, newSnapshot, edits)
                .also { lastByFile[newSnapshot.file.path] = it.tree as KotlinParsedFile }
    }

    private val completionContributor by lazy { KotlinCompletion(service) { refreshOverlay() } }
    override fun completionContributions(): List<CompletionContribution> =
        listOf(CompletionContribution(completionContributor))

    override val inlayHints: dev.ide.lang.hints.InlayHintService by lazy {
        KotlinInlayHintService(
            parsedFor = { lastByFile[it.path] },
            resolverFor = { syncFocal(it); KotlinResolver(it.ktFile, it, service) },
        )
    }

    override val signatureHelp: SignatureHelpService by lazy {
        KotlinSignatureHelpService(service) { refreshOverlay() }
    }

    override val semanticHighlighter: SemanticHighlightService by lazy {
        KotlinSemanticHighlighter(
            parsedFor = { lastByFile[it.path] },
            resolverFor = { syncFocal(it); KotlinResolver(it.ktFile, it, service) },
            refresh = { refreshOverlay() },
            externalStampFor = { service.externalContentStamp(it) },
        )
    }

    override val folding: FoldingService by lazy {
        KotlinCodeFolder(parsedFor = { lastByFile[it.path] })
    }

    /** Re-indentation + whitespace cleanup over the parse-only PSI (no IntelliJ formatting model on ART). */
    override val formatting: FormattingService = KotlinFormatter()

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        lastByFile[file.path] ?: incrementalParser.parseFull(EmptyDocument(file))

    // --- Compose preview (interpreter integration; see docs/compose-interpreter.md) ---

    /** PSI→ResolvedTree lowering for the Compose-preview interpreter, with its own per-function memoization. */
    private val previewLowering by lazy { KotlinPreviewLowering(service, ::reuseCachesFor) }

    /** The `@Preview @Composable` functions in [file]'s last parse — the editor's preview targets. */
    fun composePreviews(file: VirtualFile): List<PreviewInfo> =
        lastByFile[file.path]?.let { previewLowering.previews(it) } ?: emptyList()

    /**
     * Gutter "implementations/overrides" markers for [file]'s last parse: one per INHERITABLE type declaration
     * (an interface, or an `open`/`abstract`/`sealed` class — a `final` class / object / enum / annotation is
     * skipped, nothing can extend it) that actually has direct inheritors in the [dev.ide.index.SubtypeIndex].
     * Anchored to the type's name identifier. Only the inheritor FQNs/kinds are collected (cheap — one index
     * query per type); a click resolves a target's location via [declarationLocation]. Empty until the index
     * has built the subtype relation (navigation, so the "dumb until indexed" latency is acceptable).
     */
    fun inheritorMarkers(file: VirtualFile): List<InheritorMarker> {
        val parsed = lastByFile[file.path] ?: return emptyList()
        val out = ArrayList<InheritorMarker>()
        for (decl in com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(parsed.ktFile, KtClassOrObject::class.java)) {
            if (!canBeInherited(decl)) continue
            val fqn = decl.fqName?.asString() ?: continue
            val anchor = decl.nameIdentifier?.textRange?.startOffset ?: continue
            val subs = service.directInheritors(fqn)
            if (subs.isEmpty()) continue
            out += InheritorMarker(
                anchor,
                isInterface = decl is org.jetbrains.kotlin.psi.KtClass && decl.isInterface(),
                targets = subs.map { InheritorTarget(it.fqn, it.kind) }.sortedBy { it.fqn },
            )
        }
        return out.sortedBy { it.offset }
    }

    /** Whether a declaration can have subtypes (so an inheritors marker is meaningful): an interface or an
     *  `open`/`abstract`/`sealed` class. A `final` class (Kotlin's default), object, enum, or annotation cannot. */
    private fun canBeInherited(d: KtClassOrObject): Boolean {
        val c = d as? org.jetbrains.kotlin.psi.KtClass ?: return false
        if (c.isInterface()) return true
        if (c.isEnum() || c.isAnnotation()) return false
        return c.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPEN_KEYWORD) ||
            c.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD) ||
            c.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SEALED_KEYWORD)
    }

    /** Locate the SOURCE declaration of type [fqn] (file + its name-identifier offset) for go-to-implementation
     *  navigation, or null when [fqn] isn't declared in project source (a classpath-only inheritor has no
     *  navigable source). Parses the declaring file once (uncached) to find the offset. */
    fun declarationLocation(fqn: String): Pair<VirtualFile, Int>? {
        val psf = service.sourceFileDeclaringType(fqn) ?: return null
        val kt = dev.ide.lang.kotlin.parse.KotlinParserHost.parse(psf.file.name, psf.text)
        val decl = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(kt, KtClassOrObject::class.java)
            .firstOrNull { it.fqName?.asString() == fqn }
        val offset = decl?.nameIdentifier?.textRange?.startOffset ?: decl?.textRange?.startOffset ?: 0
        return psf.file to offset
    }

    /** Whether [file]'s last parse contains syntax errors; a preview must not interpret such a file. See
     *  [KotlinPreviewLowering.hasSyntaxErrors] for why. */
    fun hasSyntaxErrors(file: VirtualFile): Boolean =
        lastByFile[file.path]?.let { previewLowering.hasSyntaxErrors(it) } ?: false

    /** Best-effort FQN of a `@PreviewParameter` provider named by [simpleName] in [file] (imports/same package)
     *  — so the renderer can load a library provider class reflectively when it isn't project source. */
    fun previewProviderFqn(file: VirtualFile, simpleName: String): String? =
        lastByFile[file.path]?.let { previewLowering.typeFqn(it, simpleName) }

    /** Lower every top-level function in [file] to a [ResolvedFunction], keyed
     *  `"name/arity"` — the program the interpreter runs a preview against (same-file composables included). */
    fun lowerFile(file: VirtualFile): Map<String, ResolvedFunction> =
        lastByFile[file.path]?.let { previewLowering.program(it) } ?: emptyMap()

    /** Lower every source class/object/enum in [file] to a [ResolvedClass] — the
     *  project-source types a preview's program may construct or reference. */
    fun lowerFileClasses(file: VirtualFile): List<ResolvedClass> =
        lastByFile[file.path]?.let { previewLowering.classes(it) } ?: emptyList()

    /** The cross-file-expanded preview program + classes for [file]: its own program/classes plus every
     *  project-source type/top-level function it transitively reaches in OTHER files (same module), so a
     *  preview that constructs a `data class` or calls a helper declared elsewhere still interprets. Null when
     *  [file] hasn't been parsed. See [KotlinPreviewLowering.crossFileModel]. */
    fun lowerFileWithDeps(file: VirtualFile): PreviewModel? =
        lastByFile[file.path]?.let { previewLowering.crossFileModel(it) }

    /** Cross-MODULE preview model for [file]: seed from [file]'s own lowering, then run the reachable-declaration
     *  expansion over the supplied [provider] (the host's cross-module dispatcher). Null when [file] isn't parsed.
     *  This is the multi-module counterpart to [lowerFileWithDeps] — see [IdeServices] for how the provider is
     *  built (find a reached declaration across the dependency-module closure, lower it with its OWNING module's
     *  analyzer). */
    fun lowerFileWithDeps(
        file: VirtualFile, provider: PreviewDeclProvider,
    ): PreviewModel? =
        lastByFile[file.path]?.let {
            previewLowering.expand(
                previewLowering.loweredEntryFile(it),
                provider
            )
        }

    /** The source file declaring top-level type [fqn] within this module's source model (which spans its own +
     *  dependency-module sources), or null. The cross-module preview dispatcher uses this to LOCATE a reached
     *  declaration; the file is lowered by its owning module's analyzer via [loweredFile]. */
    fun findDeclaringTypeFile(fqn: String): KotlinSymbolService.PreviewSourceFile? =
        service.sourceFileDeclaringType(fqn)

    /** The source files declaring a top-level function named [name] within this module's source model. */
    fun findDeclaringFunctionFiles(name: String): List<KotlinSymbolService.PreviewSourceFile> =
        service.sourceFilesDeclaringFunction(name)

    /** Lower a single source file (located via [findDeclaringTypeFile]/[findDeclaringFunctionFiles]) with THIS
     *  module's analyzer — so a dependency module's file resolves against ITS OWN classpath. */
    fun loweredFile(pf: KotlinSymbolService.PreviewSourceFile): dev.ide.lang.kotlin.interp.PreviewFileModel? =
        previewLowering.loweredFile(pf)

    /** The incremental-analyze engine (runs the semantic checks with per-declaration caching). Holds the
     *  per-file analyze cache, so a single instance is kept for the analyzer's lifetime. */
    private val incrementalAnalysis by lazy {
        IncrementalSemanticAnalysis(
            service,
            ::freshCachesFor
        )
    }

    override suspend fun analyze(file: VirtualFile): AnalysisResult =
        KotlinPerf.trace("kt.analyze") {
            val parsed = lastByFile[file.path] ?: return@trace AnalysisResult(file, emptyList())
            KotlinPerf.span("overlay") {
                // cross-file freshness + same-file (the live buffer's own classes)
                refreshOverlay();
                syncFocal(parsed)
            }

            val diagnostics = KotlinPerf.span("semantic") {
                incrementalAnalysis.diagnostics(parsed)
            }
            AnalysisResult(
                file,
                parsed.diagnostics + diagnostics
            )
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
        refreshOverlay(); syncFocal(parsed)
        val text = parsed.ktFile.text
        val diags = incrementalAnalysis.diagnostics(parsed)
        fun coversCaret(d: Diagnostic) = offset >= d.range.start && offset <= d.range.end
        val unresolved =
            diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED && coversCaret(it) }
        val delegateOps =
            diags.filter { it.code == KotlinDiagnosticCodes.DELEGATE_OPERATOR && coversCaret(it) }
        if (unresolved.isEmpty() && delegateOps.isEmpty()) return emptyList()
        val insertOffset = KotlinImportEdits.insertOffset(parsed.ktFile)
        val existing =
            parsed.ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<KotlinImportFix>()
        fun offer(fqn: String) {
            if (fqn in existing || !seen.add(fqn)) return
            out += KotlinImportFix(
                "Import $fqn",
                listOf(DocumentEdit(insertOffset, 0, "import $fqn\n"))
            )
        }
        for (d in unresolved) {
            val name = text.substring(
                d.range.start.coerceIn(0, text.length),
                d.range.end.coerceIn(0, text.length)
            )
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

    /**
     * The "Implement members" quick-fix for a `kt.abstractNotImplemented` diagnostic anchored at [offset] (the
     * class name): generate `override` stubs for the inherited abstract members [cls] leaves unimplemented and
     * insert them into the class body (creating a `{ }` body when the class has none). Null when the class
     * isn't found or nothing is actually missing (the diagnostic is stale). The stub text is the same one
     * completion's override items use ([KotlinCompletionItems.overrideStubText]).
     */
    fun implementMembersFix(file: VirtualFile, offset: Int): KotlinImportFix? {
        val parsed = lastByFile[file.path] ?: return null
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

    /** The innermost class/object enclosing [offset] (the abstract-not-implemented diagnostic anchors on its name). */
    private fun classCovering(ktFile: KtFile, offset: Int): KtClassOrObject? {
        var n: PsiElement? =
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
        fun rec(p: PsiElement) {
            if (p is KtProperty) p.delegateExpression?.textRange?.let { if (offset >= it.startOffset && offset <= it.endOffset) out += p }
            var c = p.firstChild
            while (c != null) {
                rec(c); c = c.nextSibling
            }
        }
        rec(ktFile)
        return out
    }

    // --- resolution / inference ---

    /** The file's classes/objects/functions/properties in document order with nesting depth — for the
     *  structure view and sticky scroll headers. Purely syntactic (PSI), so it's safe before the index is ready. */
    override fun fileStructure(file: VirtualFile, text: CharSequence): List<StructureItem> {
        val ktFile = KotlinParserHost.parse(file.name, text)
        val out = ArrayList<StructureItem>()
        for (d in ktFile.declarations) collectStructure(d, 0, out)
        return out
    }

    private fun collectStructure(decl: KtDeclaration, depth: Int, out: MutableList<StructureItem>) {
        when (decl) {
            is KtEnumEntry -> addStructure(
                decl.name,
                null,
                SymbolKind.ENUM_CONSTANT,
                decl,
                depth,
                out
            )

            is KtClassOrObject -> {
                val kind = when {
                    decl is KtClass && decl.isInterface() -> SymbolKind.INTERFACE
                    decl is KtClass && decl.isEnum() -> SymbolKind.ENUM
                    decl is KtClass && decl.isAnnotation() -> SymbolKind.ANNOTATION_TYPE
                    else -> SymbolKind.CLASS
                }
                addStructure(decl.name, null, kind, decl, depth, out)
                for (m in decl.declarations) collectStructure(m, depth + 1, out)
            }

            is KtNamedFunction -> addStructure(
                decl.name,
                "(${paramTypes(decl.valueParameters)})",
                SymbolKind.METHOD,
                decl,
                depth,
                out
            )

            is KtSecondaryConstructor -> addStructure(
                "constructor",
                "(${paramTypes(decl.valueParameters)})",
                SymbolKind.CONSTRUCTOR,
                decl,
                depth,
                out
            )

            is KtProperty -> addStructure(
                decl.name,
                decl.typeReference?.text,
                SymbolKind.FIELD,
                decl,
                depth,
                out
            )

            else -> {}
        }
    }

    private fun paramTypes(params: List<org.jetbrains.kotlin.psi.KtParameter>): String =
        params.joinToString(", ") { it.typeReference?.text ?: it.name ?: "" }

    private fun addStructure(
        name: String?,
        detail: String?,
        kind: SymbolKind,
        element: KtElement,
        depth: Int,
        out: MutableList<StructureItem>
    ) {
        val n = name ?: return
        val nameOffset = (element as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
            ?: element.textRange.startOffset
        out.add(StructureItem(n, detail, kind, nameOffset, element.textRange.endOffset, depth))
    }

    /** Quick documentation for the symbol at [offset]: a declaration the caret sits ON is documented directly
     *  (raw KDoc from its PSI); otherwise the reference under the caret is resolved to a symbol. */
    override fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? {
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

    override fun resolve(node: DomNode): ResolveResult {
        val kdn = node as? KotlinDomNode ?: return ResolveResult.Unresolved
        refreshOverlay() // go-to-definition must reach a symbol just declared in another open file
        val parsed = kdn.owner
        syncFocal(parsed) // ...and one declared in the same buffer being edited
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
        val psi = kdn.psi as? KtNameReferenceExpression ?: return ResolveResult.Unresolved
        val name = psi.getReferencedName()
        val q = psi.parent as? KtQualifiedExpression
        val sym: Symbol? = if (q != null && q.selectorExpression === psi) {
            // A bare type-parameter receiver (`t.member` where `t: T`, `<T : Bound>`) navigates to the member of
            // the parameter's upper bound; a normal receiver is unchanged (see receiverForMembers).
            resolver.inferType(q.receiverExpression)
                ?.let { resolver.receiverForMembers(it, q.receiverExpression.textRange.startOffset) }
                ?.let { recv -> service.membersNamed(recv.qualifiedName, recv.typeArguments, name).firstOrNull() }
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

    private class KotlinScope(private val offset: Int, private val resolver: KotlinResolver) :
        Scope {
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
