package dev.ide.lang.kotlin

import dev.ide.index.IndexService
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
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
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
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.LazyPreviewDeclProvider
import dev.ide.lang.kotlin.interp.PreviewFileModel
import dev.ide.lang.kotlin.interp.PreviewInfo
import dev.ide.lang.kotlin.interp.PreviewLazyFile
import dev.ide.lang.kotlin.interp.PreviewLoweringDiskCache
import dev.ide.lang.kotlin.interp.PreviewModel
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * The editor-time engine for Kotlin. Tolerant PSI parse to neutral DOM, plus resolution, the inference
 * subset, and member/name/type completion, all on an independent symbol model (the compiler is used only to
 * parse). Mirrors [dev.ide.lang.jdt.JdtSourceAnalyzer]: built from a model-derived [CompilationContext],
 * with [indexService] injected by the host after construction (it powers type-NAME completion via
 * `java.classNames`; members come from bytecode).
 */
/** Android framework FQN prefixes hidden from index-backed type-name completion in a non-Android module. */
private val ANDROID_TYPE_PREFIXES = listOf("android.", "androidx.", "com.android.", "com.google.android.", "dalvik.")

/**
 * Whether `android.*`/`androidx.*` type names should stay VISIBLE in this module's type-name completion,
 * i.e. NOT be hidden as "another module's classpath leaking through the shared index". True when either the
 * host says this is an Android module ([isAndroidModule] `== true`), OR the Android world is actually on this
 * module's own [classpathJars] — an `android.jar` boot classpath (by name, for a desktop SDK) OR any jar under
 * an `androidx`/`android`/`com.android`/`com.google.android` coordinate group. The classpath check is read
 * from the resolved jar PATHS, whose Maven layout encodes the group (`…/androidx/compose/ui/…`), so it holds
 * where the filename sniff cannot: an on-device `android.jar` bundled under another name, a Compose module
 * whose `AndroidFacet` didn't decode (android-support disabled), or a plain `kotlin-*`/JVM Compose-Multiplatform
 * module. Crucially a `false`/`null` from the host does NOT force hiding — on-classpath evidence still wins, so
 * `androidx.compose.ui.Modifier` is offered wherever Compose is a real dependency.
 */
internal fun androidNamespacesVisible(isAndroidModule: Boolean?, classpathJars: List<Path>): Boolean {
    if (isAndroidModule == true) return true
    return classpathJars.any { jar ->
        jar.fileName?.toString() == "android.jar" || run {
            val p = "/" + jar.toString().replace('\\', '/').trim('/') + "/"
            p.contains("/androidx/") || p.contains("/com/android/") || p.contains("/com/google/android/")
        }
    }
}

class KotlinSourceAnalyzer(ctx: CompilationContext) : SourceAnalyzer, Disposable {

    /** Injected by the host (ide-core's `analyzerFor`). */
    @Volatile
    var indexService: IndexService? = null

    /** Injected by the host: where to persist the classpath extension scan across launches. */
    @Volatile
    var extensionCacheDir: Path? = null

    /** Injected by the host: where to persist lowered Compose-preview declarations across launches, so the
     *  first preview after a project reopen decodes instead of re-running overload resolution (the dominant
     *  cold cost on a big project). Null → in-memory lowering cache only. Must be set before the first preview
     *  use (the host sets it at analyzer creation, like [extensionCacheDir]). */
    @Volatile
    var previewLoweringCacheDir: Path? = null

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

    /** Injected by the host: whether this module targets Android (has an `AndroidFacet`, or an `android-*`
     *  module type). When `true` it forces `android.*`/`androidx.*` type names to stay VISIBLE in completion;
     *  otherwise the decision falls to the module's own classpath (see [androidNamespacesVisible]). A `false`
     *  is NOT taken as "hide them" — the analyzer still offers the namespaces whenever their jars are on this
     *  module's classpath — so a Compose module whose `AndroidFacet` didn't decode (e.g. android-support
     *  disabled) or a `kotlin-*`-typed Compose-Multiplatform module never loses `androidx.compose.ui.Modifier`. */
    @Volatile
    var isAndroidModule: Boolean? = null

    /** Injected by the host: the current live editor buffers (VirtualFile path → text) for CROSS-file
     *  freshness — a declaration just typed in ANOTHER open file resolves/completes here before it is saved
     *  and reindexed. Pushed into the symbol model at each analyze/complete/resolve; the model diffs the
     *  buffers and reparses only what changed (a no-op when nothing did), so the refresh is cheap. */
    @Volatile
    var liveOverlayProvider: () -> Map<String, String> = { emptyMap() }

    /** Injected by the host: editor synthetic-member providers (kotlinx.serialization's `serializer()`, …) —
     *  compiler-plugin-generated members the parse-only model can't see. Defaults to the built-ins so serialization
     *  editor support works even in direct/test wiring; ide-core overrides with the `platform.kotlinSyntheticMember`
     *  EP contributions (falling back to the same built-ins). See [KotlinSyntheticMemberProvider]. */
    @Volatile
    var syntheticMemberProviders: () -> List<dev.ide.lang.kotlin.symbols.KotlinSyntheticMemberProvider> =
        { dev.ide.lang.kotlin.symbols.BUILTIN_KOTLIN_SYNTHETIC_MEMBER_PROVIDERS }

    /** Injected by the host: members of a currently-OPEN same-project Java SOURCE class, parsed from its live
     *  editor buffer (keyed/encoded like the `java.membersByOwner` index). Lets a Kotlin file see an UNSAVED
     *  Java edit — a new method, a changed signature — before it is saved and reindexed. Null for an fqn no
     *  open `.java` buffer declares, so the save-driven index answers instead. See [KotlinSymbolService]. */
    @Volatile
    var javaSourceMemberProvider: (String) -> List<dev.ide.index.MemberValue>? = { null }

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
            // The symbol service names classpath entries as strings, since it is the half of this backend
            // that has to compile without `java.nio.file`.
            classpathJars.map { it.toString() },
            indexService,
            extensionCacheDir?.toString(),
            { syntheticClassProvider() },
            sourceDocProvider,
            // Hide the Android namespaces ONLY from a module that genuinely can't use them (see
            // [androidNamespacesVisible]) — never purely because the host's Android-ness flag was false/unset.
            // Hide the Android namespaces ONLY from a module that genuinely can't use them (see
            // [androidNamespacesVisible]) — never purely because the host's Android-ness flag was false/unset.
            excludedTypePrefixes = if (androidNamespacesVisible(isAndroidModule, classpathJars)) emptyList() else ANDROID_TYPE_PREFIXES,
            syntheticMemberProviders = { syntheticMemberProviders() },
            javaSourceMemberProvider = { javaSourceMemberProvider(it) },
        )
    }
    private val service: KotlinSymbolService get() = serviceLazy.value

    /** Whether the classpath symbol model can resolve library symbols yet. False during "dumb mode" (the
     *  workspace index is still building on first launch), the window in which library callables resolve to
     *  0 candidates. Mirrors the gate the editor's diagnostics/completion already honor; the Compose preview
     *  uses it to tell a still-warming classpath from a genuinely unsupported construct. */
    fun classpathReady(): Boolean = runCatching { service.classpathReady() }.getOrDefault(true)

    /** Whether the classpath index is still BUILDING. The Compose preview gates on this rather than
     *  [classpathReady] so a finished-but-partial index (a skipped/undecoded jar never flips `ready`) doesn't
     *  wedge it at "Preparing" forever — resolution still answers from the open segments. See
     *  [dev.ide.lang.kotlin.symbols.KotlinSymbolService.classpathIndexBuilding]. */
    fun classpathIndexBuilding(): Boolean = runCatching { service.classpathIndexBuilding() }.getOrDefault(false)

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

    // A single keystroke resolves the SAME snapshot from several passes — diagnostics (incrementalAnalysis),
    // semantic highlight (callee classification), inlay hints, and the Compose preview lowerer — each through its
    // own KotlinResolver. Each pass recomputes inference + overload resolution + member enumeration from cold
    // (measured on-device: highlight's `hl.call` alone ~800ms on a member-heavy file, most of it re-resolving
    // callees the diagnostics pass already resolved). Sharing the per-snapshot memo caches across those passes
    // means only the FIRST pays; the rest hit the memos. Keyed by file path; the prior entry is unreachable once
    // replaced. Safe: every engine lane runs on EngineScheduler's single serialized worker, so the shared caches
    // are never touched concurrently, and each pass keeps its own transient resolver state (narrowings/
    // reentrancy) — only the pure memo caches are shared.
    private class CachesEntry(val parsed: KotlinParsedFile, val externalStamp: Long, val caches: KotlinResolverCaches)

    private val cachesBySnapshot = ConcurrentHashMap<String, CachesEntry>()

    /**
     * The per-snapshot resolver memo caches shared across a keystroke's passes. Reuse is gated on snapshot
     * IDENTITY *and* the external content stamp: a cross-file dependency edit leaves this file's own snapshot
     * unchanged (same [parsed] object) but bumps the stamp, so it forces a fresh cache — the resolution memos
     * would otherwise stale-serve the old cross-file shape (caught by
     * `crossFileDependencyEditInvalidatesDependentCache`). Within one keystroke (same snapshot, same stamp) every
     * pass reuses the one cache; the first to run builds it.
     */
    private fun sharedCachesFor(parsed: KotlinParsedFile): KotlinResolverCaches {
        val stamp = service.externalContentStamp(parsed.file.path)
        cachesBySnapshot[parsed.file.path]?.let { if (it.parsed === parsed && it.externalStamp == stamp) return it.caches }
        val c = KotlinResolverCaches()
        cachesBySnapshot[parsed.file.path] = CachesEntry(parsed, stamp, c)
        return c
    }

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            // A settled buffer is parseFull'd for several features in succession (analyze, semantic highlight,
            // breadcrumb, …). The PSI parse is pure for a given text, so reuse the last parse when the text is
            // unchanged instead of re-running the parser each time.
            val prev = lastByFile[snapshot.file.path]
            if (prev != null && prev.ktFile.text.contentEquals(snapshot.text)) return prev
            // Text changed: parse it again. The in-place subtree reparse that used to sit here was a property
            // of PSI's mutable tree, and the vendored parser builds an immutable one. Its own answer to the
            // same cost is `IncrementalKotlinParse` (lazy bodies, expand on demand, skip the file parse when
            // the edit provably stays inside one body), which is a separate change and not wired here yet.
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
            resolverFor = { syncFocal(it); KotlinResolver(it.ktFile, it, service, sharedCachesFor(it)) },
        )
    }

    override val signatureHelp: SignatureHelpService by lazy {
        KotlinSignatureHelpService(service) { refreshOverlay() }
    }

    override val semanticHighlighter: SemanticHighlightService by lazy {
        KotlinSemanticHighlighter(
            parsedFor = { lastByFile[it.path] },
            resolverFor = { syncFocal(it); KotlinResolver(it.ktFile, it, service, sharedCachesFor(it)) },
            refresh = { refreshOverlay() },
            externalStampFor = { service.externalContentStamp(it) },
        )
    }

    override val folding: FoldingService by lazy {
        KotlinCodeFolder(parsedFor = { lastByFile[it.path] })
    }

    /** Re-indentation + whitespace cleanup over the parse-only PSI (no IntelliJ formatting model on ART). */
    override val formatting: FormattingService = KotlinFormatter()

    /** "Optimize Imports": sort/dedupe/collapse + drop unused, over the parse-only PSI. */
    override val importOrganizer: ImportOrganizerService = KotlinImportOrganizer()

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        lastByFile[file.path] ?: incrementalParser.parseFull(EmptyDocument(file))

    // --- Compose preview (interpreter integration; see docs/compose-interpreter.md) ---

    /** PSI→ResolvedTree lowering for the Compose-preview interpreter, with its own per-declaration memoization
     *  (and disk persistence when the host provided [previewLoweringCacheDir]). The disk salt is this module's
     *  classpath jar fingerprint: a dependency change alters overload resolution, so stale entries must miss —
     *  the same signal that makes the host dispose this whole analyzer on classpath change. */
    private val previewLowering by lazy {
        val disk = previewLoweringCacheDir?.let { dir ->
            runCatching {
                val fp = classpathJars.asSequence()
                    .map { p ->
                        val a = runCatching { Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull()
                        "$p:${a?.size() ?: -1}:${a?.lastModifiedTime()?.toMillis() ?: -1}"
                    }
                    .sorted().joinToString("|").hashCode().toString(16)
                PreviewLoweringDiskCache(dir, "cp=$fp")
            }.getOrNull()
        }
        KotlinPreviewLowering(service, ::sharedCachesFor, disk)
    }

    /** The `@Preview @Composable` functions in [file]'s last parse — the editor's preview targets. */
    fun composePreviews(file: VirtualFile): List<PreviewInfo> =
        lastByFile[file.path]?.let { previewLowering.previews(it) } ?: emptyList()

    /**
     * The editor features that need no build: navigation, quick doc, the quick fixes.
     *
     * They live in [KotlinEditorFeatures] (commonMain) because none of them names a `CompilationContext`,
     * and a host without one -- the iOS app -- needs exactly these. This class keeps the entry points so
     * its callers are unchanged, and supplies the four things the features cannot know: the parse cache
     * and the two freshness hooks, plus the diagnostics the fixes are offered for.
     */
    private val features by lazy {
        KotlinEditorFeatures(
            service = service,
            analysis = incrementalAnalysis,
            parsedFor = { path -> lastByFile[path] },
            refreshOverlay = ::refreshOverlay,
            syncFocal = ::syncFocal,
        )
    }

    fun inheritorMarkers(file: VirtualFile): List<InheritorMarker> = features.inheritorMarkers(file)

    fun declarationLocation(fqn: String): Pair<VirtualFile, Int>? = features.declarationLocation(fqn)

    fun navigationTargets(file: VirtualFile, text: CharSequence, offset: Int, kind: NavKind): List<NavTarget> =
        features.navigationTargets(file, text, offset, kind)

    fun navigationOptions(file: VirtualFile, text: CharSequence, offset: Int): List<Pair<NavKind, List<NavTarget>>> =
        features.navigationOptions(file, text, offset)

    fun builtinStub(fqn: String): String? = features.builtinStub(fqn)

    /** Whether [file]'s last parse contains syntax errors; a preview must not interpret such a file. See
     *  [KotlinPreviewLowering.hasSyntaxErrors] for why. */
    fun hasSyntaxErrors(file: VirtualFile): Boolean =
        lastByFile[file.path]?.let { previewLowering.hasSyntaxErrors(it) } ?: false

    /**
     * The messages of [file]'s ERROR diagnostics that must stop the Compose preview interpreting it — the
     * [KotlinDiagnosticCodes.PREVIEW_BLOCKING] codes, which documents why this set and not "any error".
     *
     * This is the gate for the errors that survive [hasSyntaxErrors]: half-typed code very often parses into a
     * clean tree (the parser recovers) and lowers into a clean tree too, and only then feeds the real Compose
     * runtime a value of the wrong shape. Runs the same semantic pass the editor runs per keystroke, over the
     * same snapshot and the same per-declaration cache, so on the preview's (debounced, later) pass it is
     * normally a cache hit; the analysis it does pay for is a fraction of the lowering it saves by refusing.
     */
    fun previewBlockingErrors(file: VirtualFile): List<String> {
        val parsed = lastByFile[file.path] ?: return emptyList()
        refreshOverlay()
        syncFocal(parsed)
        return (parsed.diagnostics + incrementalAnalysis.diagnostics(parsed))
            .filter { it.severity == Severity.ERROR && it.code in KotlinDiagnosticCodes.PREVIEW_BLOCKING }
            .map { it.message }
    }

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
     *  This is the multi-module counterpart to [lowerFileWithDeps] — see `ComposePreviewService` for how the
     *  provider is built (find a reached declaration across the dependency-module closure, lower it with its
     *  OWNING module's analyzer). */
    fun lowerFileWithDeps(
        file: VirtualFile, provider: LazyPreviewDeclProvider,
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
    fun loweredFile(pf: KotlinSymbolService.PreviewSourceFile): PreviewFileModel? =
        previewLowering.loweredFile(pf)

    /** The lazily-lowering handle for [pf], lowered by THIS module's analyzer on demand — the cross-module
     *  expansion pulls exactly the reached declaration through it instead of materializing the whole file. */
    fun lazyLoweredFile(pf: KotlinSymbolService.PreviewSourceFile): PreviewLazyFile? =
        previewLowering.lazyFile(pf)

    /** The incremental-analyze engine (runs the semantic checks with per-declaration caching). Holds the
     *  per-file analyze cache, so a single instance is kept for the analyzer's lifetime. */
    private val incrementalAnalysis by lazy {
        IncrementalSemanticAnalysis(
            service,
            ::sharedCachesFor
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

    fun importFixesAt(file: VirtualFile, offset: Int): List<KotlinImportFix> = features.importFixesAt(file, offset)

    fun implementMembersFix(file: VirtualFile, offset: Int): KotlinImportFix? = features.implementMembersFix(file, offset)

    fun suspendModifierFix(file: VirtualFile, offset: Int): KotlinImportFix? = features.suspendModifierFix(file, offset)

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

    override fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? =
        features.quickDoc(file, text, offset)

    override fun resolve(node: DomNode): ResolveResult = features.resolve(node)

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
