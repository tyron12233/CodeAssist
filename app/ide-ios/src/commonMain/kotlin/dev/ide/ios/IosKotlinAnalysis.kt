package dev.ide.ios

import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.index.impl.ClasspathIndex
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.TextRange
import dev.ide.lang.dom.expandSelection
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.IncrementalSemanticAnalysis
import dev.ide.lang.kotlin.KotlinDiagnosticCodes
import dev.ide.lang.kotlin.KotlinEditorFeatures
import dev.ide.lang.kotlin.KotlinFormatter
import dev.ide.lang.kotlin.KotlinImportFix
import dev.ide.lang.kotlin.KotlinImportOrganizer
import dev.ide.lang.kotlin.KotlinInlayHintService
import dev.ide.lang.kotlin.KotlinLanguage
import dev.ide.lang.kotlin.KotlinSignatureHelpService
import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.kotlin.NavTarget
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.KotlinResolverCaches
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.platform.ConcurrentMap
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile

/**
 * The Kotlin editor, bound to one open project.
 *
 * `:lang-kotlin`'s editor half builds for this target, so the analysis here is the SAME code the desktop and
 * Android hosts run — the symbol table, the resolver, the inference subset, the completion contributor. What
 * it is NOT given is the desktop host's `KotlinSourceAnalyzer`, which is the adapter binding that half to a
 * `CompilationContext` (a module of a real build) and stays on the JVM. This host has no build, so it drives
 * the symbol service directly, which is all the adapter ever did for completion.
 *
 * [classpathJars] is what the project can see BEYOND its own sources — the jars [IosDependencies] has on disk.
 * It may be empty, and the editor still works: project types resolve, library ones do not. That is the
 * difference between a freshly-created project on a phone with no signal and one whose stdlib has been
 * fetched once.
 *
 * One instance per open project, disposed when the project changes: the symbol service caches a whole source
 * model, and pointing it at a second project would serve the first one's declarations.
 */
internal class IosKotlinAnalysis(
    private val projectRoot: String,
    classpathJars: List<String> = emptyList(),
    /**
     * The live editor buffers, OWNED BY THE HOST and shared with it.
     *
     * Shared rather than held here because the two ends run on different threads: the editor records a
     * keystroke from the UI thread, and everything below reads it from the analysis thread. A [ConcurrentMap]
     * is what makes that write cheap enough to do on the UI thread at all -- registering a buffer must not be
     * what constructs this object, which reads every jar on the classpath.
     */
    private val buffers: ConcurrentMap<String, String> = ConcurrentMap(),
    /** Reports the index build's progress to the host, per jar read; see [ClasspathIndex.build]. */
    onIndexProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
) {

    /**
     * The classpath as an INDEX, which is what turns library code from resolvable into discoverable.
     *
     * The jars alone answer "what is `kotlin.collections.List`" and "what are its members". They cannot
     * answer "what callables are named `println`" or "what types start with `Str`" — those are inverted
     * lookups, and without them completion offers nothing from a library and the checks cannot honestly
     * call a name unresolved. Null when there is no classpath to index yet.
     */
    private val index: ClasspathIndex? = classpathJars.takeIf { it.isNotEmpty() }?.let { jars ->
        runCatching {
            ClasspathIndex.build(
                jars,
                INDEXES,
                IosFiles.join(projectRoot, ".platform/caches/index"),
                onProgress = onIndexProgress,
            )
        }.getOrNull()
    }

    private val service = KotlinSymbolService(
        sourceRoots = listOf<VirtualFile>(IosVirtualFile(projectRoot)),
        classpathJars = classpathJars,
        index = index,
        // Persist the classpath extension scan, so a second open reuses it instead of re-reading every
        // class in every jar. Its own directory, beside the resolver's cache rather than inside it.
        cacheDir = IosFiles.join(projectRoot, ".platform/caches/kotlin-ext"),
    )

    private val completion = KotlinCompletion(service)

    /**
     * The semantic checks, with their per-declaration reuse cache.
     *
     * One instance for the project, not one per call: the cache is the reason a keystroke re-checks the
     * touched declaration instead of the whole file, and a fresh instance per call would throw it away.
     *
     * `resolveReady` is left to the symbol service, which reads it off the index's status: complete when
     * there is one, and — through `?: true` — also "complete" when there is none. That second case is the
     * one that put "Unresolved reference: println" in the app's own starter file, so [index] being absent
     * is exactly when the classpath-dependent checks must not run; see [analyze].
     */
    private val analysis = IncrementalSemanticAnalysis(service, resolveReady = { index != null })

    private var documentVersion = 0L

    /**
     * The last parse of each file, reused while its text is unchanged.
     *
     * One keystroke asks this class for diagnostics and then inlay hints, both over the SAME buffer. Parsing
     * is pure for a given text, so without this each pass would re-run the parser on the whole file and then
     * throw the tree away, two parses where one will do. It is also what lets the hint service be given a
     * `parsedFor` lambda at all: it is handed a file and reads the parse the editor already has rather than
     * making its own.
     */
    private val lastByFile = HashMap<String, KotlinParsedFile>()

    /**
     * Resolver memo caches shared by the passes that run over one snapshot.
     *
     * Each pass builds its own [KotlinResolver], and a resolver recomputes inference, overload resolution and
     * member enumeration from cold. Sharing the pure memo caches across the passes of a single keystroke means
     * only the first pays. Keyed by path and gated on snapshot identity AND the external content stamp, so an
     * edit in ANOTHER file (which leaves this file's snapshot untouched) still forces a fresh cache rather
     * than stale-serving the old cross-file shape.
     */
    private class CachesEntry(
        val parsed: KotlinParsedFile,
        val externalStamp: Long,
        val caches: KotlinResolverCaches,
    )

    private val cachesBySnapshot = HashMap<String, CachesEntry>()

    private fun sharedCachesFor(parsed: KotlinParsedFile): KotlinResolverCaches {
        val stamp = service.externalContentStamp(parsed.file.path)
        cachesBySnapshot[parsed.file.path]?.let {
            if (it.parsed === parsed && it.externalStamp == stamp) return it.caches
        }
        return KotlinResolverCaches().also {
            cachesBySnapshot[parsed.file.path] = CachesEntry(parsed, stamp, it)
        }
    }

    /**
     * Register the file being queried as the model's focal source, built from the parse in hand.
     *
     * Without it a declaration typed in THIS file is invisible to the resolver until the model is rebuilt
     * from disk or overlay, so a `val` would be hinted against the previous version of its own file. Keyed by
     * text hash inside the service, so a repeated query on unchanged text costs nothing.
     */
    private fun syncFocal(parsed: KotlinParsedFile) {
        runCatching {
            service.syncFocal(parsed.file.path, parsed.ktFile.text.hashCode()) {
                SourceIndexBuilder.extractFrom(parsed.ktFile, parsed, parsed.file.path)
            }
        }
    }

    private fun resolverFor(parsed: KotlinParsedFile): KotlinResolver {
        syncFocal(parsed)
        return KotlinResolver(parsed.ktFile, parsed, service, sharedCachesFor(parsed))
    }

    // The editor capabilities this host can offer, all of them the same implementations the desktop and
    // Android hosts run. `KotlinSourceAnalyzer` wires these on the JVM out of a build's CompilationContext;
    // here they are wired out of the symbol service directly, which is everything they actually need.
    //
    // NOT here: `KotlinSemanticHighlighter`, the type-aware coloring. It is portable code and it builds for
    // this target, but REFERENCING it fails the link: Kotlin/Native 2.4.0's `CastsOptimization` recurses until
    // its own stack overflows on that class, and the depth is cumulative across several of its functions
    // rather than caused by any one of them (bisected: gutting `enclosingClassMemberProperty`,
    // `topLevelPropertyInFile`, `classifyCallableRef` and `isTypeParameterInScope` together links, and no
    // smaller subset does). Fixing it means restructuring the JVM hosts' hot highlight path against a compiler
    // bug, which is its own task. Until then the editor here keeps the lexer's coloring.
    private val inlays by lazy {
        KotlinInlayHintService(parsedFor = { lastByFile[it.path] }, resolverFor = ::resolverFor)
    }

    /**
     * Navigation, quick documentation and the quick fixes.
     *
     * The same [KotlinEditorFeatures] the JVM hosts run: they were members of `KotlinSourceAnalyzer` (the
     * adapter onto a build) until it turned out that none of them needs a build at all. The two freshness
     * hooks are what this host has to supply -- [syncOverlay] for a declaration typed in another tab,
     * [syncFocal] for one typed in this buffer -- and without them navigation would only reach saved code.
     */
    private val features by lazy {
        KotlinEditorFeatures(
            service = service,
            analysis = analysis,
            parsedFor = { lastByFile[it] },
            refreshOverlay = ::syncOverlay,
            syncFocal = ::syncFocal,
        )
    }

    private val signatures by lazy { KotlinSignatureHelpService(service) }

    private val formatter = KotlinFormatter()

    private val importOrganizer = KotlinImportOrganizer()

    /**
     * Record [text] as the live content of [path], then bring the symbol model in line with every buffer.
     *
     * Not just bookkeeping: without it a declaration typed in one tab is invisible to completion in another
     * until the file is saved, because the source model would still be reading the file from disk.
     *
     * Called on the analysis thread at the top of every pass. Cheap when nothing moved: `setOverlay` diffs
     * against what it already holds and returns without invalidating anything when the content is equal, so
     * re-pushing an unchanged snapshot on each pass costs one map comparison.
     */
    fun updateDocument(path: String, text: String) {
        if (isKotlin(path)) buffers[path] = text
        syncOverlay()
    }

    /**
     * Push the host's buffers into the symbol model, and drop the cached parses of files no longer open.
     *
     * The prune lives here rather than in a `documentClosed` hook because closing a tab happens on the UI
     * thread and these caches belong to the analysis thread. A path leaves [buffers] the moment its tab
     * closes; the next pass notices and lets its tree go, so nothing outlives the editor that asked for it.
     */
    private fun syncOverlay() {
        // Only Kotlin buffers, though the host records every open file (search reads the same map). A `.md`
        // tab pushed in here would be a path the source model has no use for, and `setOverlay` would drop the
        // cached module model on every keystroke in it — a full rebuild of the Kotlin world for editing a
        // readme. Read through a null check rather than `orEmpty()`: a tab closing between the key snapshot
        // and the read must not be published as a file that has become empty.
        val open = HashMap<String, String>()
        for (path in buffers.keys) {
            if (!isKotlin(path)) continue
            buffers[path]?.let { open[path] = it }
        }
        service.setOverlay(open)
        if (lastByFile.keys.any { it !in open }) {
            lastByFile.keys.retainAll(open.keys)
            cachesBySnapshot.keys.retainAll(open.keys)
        }
    }

    /**
     * The parse of [text] for [path], reusing the last one while the text is unchanged.
     *
     * The reuse is what makes the passes of one keystroke cost a single parse; it also has to be an identity
     * reuse rather than an equal-value one, because [sharedCachesFor] keys its memo caches on the snapshot
     * object.
     */
    private fun parsed(path: String, text: String): KotlinParsedFile {
        lastByFile[path]?.let { if (it.ktFile.text.contentEquals(text)) return it }
        val ktFile = KotlinParserHost.parse(IosFiles.nameOf(path), text)
        return KotlinParsedFile(ktFile, IosVirtualFile(path), ++documentVersion)
            .also { lastByFile[path] = it }
    }

    /**
     * Go-to targets of [kind] at [offset], for Go to Declaration / Implementation(s) / Type / Super.
     *
     * A target inside the project carries the file it is declared in; a library one carries a
     * `library://<fqn>` path the host has no reader for yet, so those are dropped rather than offered as a
     * tab that would open empty.
     */
    fun navigationTargets(path: String, text: String, offset: Int, kind: NavKind): List<NavTarget> {
        if (!isKotlin(path)) return emptyList()
        updateDocument(path, text)
        return features.navigationTargets(IosVirtualFile(path), text, offset, kind).filter(::isOpenable)
    }

    /** The navigation actions applicable at [offset], each with the targets it resolves to. */
    fun navigationOptions(path: String, text: String, offset: Int): List<Pair<NavKind, List<NavTarget>>> {
        if (!isKotlin(path)) return emptyList()
        updateDocument(path, text)
        return features.navigationOptions(IosVirtualFile(path), text, offset)
            .mapNotNull { (kind, targets) -> targets.filter(::isOpenable).takeIf { it.isNotEmpty() }?.let { kind to it } }
    }

    /**
     * Whether a navigation target is one this host can actually open.
     *
     * `library://` targets are compiled classes the other hosts show decompiled or from attached sources,
     * through a decompiler that is JVM-only. Offering one here would open a tab that cannot be filled.
     */
    private fun isOpenable(target: NavTarget): Boolean = !target.file.path.startsWith("library://")

    /**
     * Expand Selection: the smallest syntactic range that strictly encloses the current one.
     *
     * The walk itself is language-neutral and lives on the DOM (`dev.ide.lang.dom.expandSelection`); all
     * this host supplies is the parse, which it already has.
     */
    fun expandSelection(path: String, text: String, selStart: Int, selEnd: Int): TextRange? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        return expandSelection(parsed(path, text), selStart, selEnd, text.length)
    }

    /** Quick documentation for the symbol at [offset], or null when nothing there is documented. */
    fun quickDoc(path: String, text: String, offset: Int): QuickDocInfo? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        return features.quickDoc(IosVirtualFile(path), text, offset)
    }

    /**
     * The code actions at [offset]: the import fixes, plus the two diagnostic-keyed ones.
     *
     * Ordered and gated as the JVM providers are (`KotlinAnalysisSupport`): imports are offered wherever an
     * unresolved reference covers the caret, while implement-members and the suspend modifier are offered
     * only where their own diagnostic is, so the lightbulb never lists a fix for a problem that is not
     * there. Recomputed rather than cached between listing and applying: the buffer can change in between,
     * and applying an edit computed against older text is how a fix lands in the wrong place.
     */
    fun codeActions(path: String, text: String, offset: Int): List<KotlinImportFix> {
        if (!isKotlin(path)) return emptyList()
        updateDocument(path, text)
        val file = IosVirtualFile(path)
        val out = ArrayList<KotlinImportFix>(features.importFixesAt(file, offset))
        val covering = analysis.diagnostics(parsed(path, text))
            .filter { offset >= it.range.start && offset <= it.range.end }
            .map { it.code }
            .toSet()
        if (KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED in covering) {
            features.implementMembersFix(file, offset)?.let { out += it }
        }
        if (KotlinDiagnosticCodes.SUSPEND_OVERRIDE in covering) {
            features.suspendModifierFix(file, offset)?.let { out += it }
        }
        return out
    }

    /** Completion at [offset] in [text], or null when this is not a file this host analyzes. */
    suspend fun complete(path: String, text: String, offset: Int): CompletionResult? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        // The focal file is built from the LIVE buffer, so a declaration being typed resolves in the same
        // file it is typed in — same-file freshness, which is most of what completion is asked for.
        val snapshot = IosDocument(text, IosVirtualFile(path))
        return completion.complete(CompletionRequest(snapshot, offset, CompletionTrigger.Explicit), KotlinLanguage.ID)
    }

    /**
     * Diagnostics for the live buffer, or null when this is not a file this host analyzes.
     *
     * The buffer goes into the overlay first, so the file is analyzed as TYPED rather than as saved — which
     * is the whole point of analyzing on a keystroke.
     */
    fun analyze(path: String, text: String): List<Diagnostic>? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        return analysis.diagnostics(parsed(path, text))
    }

    /** Inlay hints for `[start, end)` — inferred types, lambda receivers, parameter names at call sites. */
    suspend fun inlayHints(path: String, text: String, start: Int, end: Int): List<InlayHint>? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        val parsed = parsed(path, text)
        return inlays.hints(parsed.file, TextRange(start, end))
    }

    /** Parameter info for the call around [offset], or null when the caret is not inside a resolvable call. */
    suspend fun signatureHelp(path: String, text: String, offset: Int): SignatureHelp? {
        if (!isKotlin(path)) return null
        updateDocument(path, text)
        val snapshot = IosDocument(text, IosVirtualFile(path))
        return signatures.signatureHelp(
            SignatureHelpRequest(snapshot, offset, SignatureHelpTrigger.CursorUpdate),
        )
    }

    /**
     * Reformat the whole buffer, or only the part overlapping `[start, end)` when [start] is given.
     *
     * Text-driven, like the formatter itself: it re-parses what it is handed rather than reading the symbol
     * model, so this neither pushes the overlay nor takes a parse from the cache. Reformatting depends on
     * nothing outside the buffer, which is why it is the one pass here that cannot be wrong for want of a
     * classpath.
     */
    suspend fun format(path: String, text: String, start: Int = -1, end: Int = -1): List<DocumentEdit>? {
        if (!isKotlin(path)) return null
        val file = IosVirtualFile(path)
        return if (start < 0) formatter.format(file, text, FORMAT_STYLE)
        else formatter.formatRange(file, text, TextRange(start, end), FORMAT_STYLE)
    }

    /** Reorder, de-duplicate and drop-unused the file's imports. Text-driven, like [format]. */
    suspend fun optimizeImports(path: String, text: String): List<DocumentEdit>? {
        if (!isKotlin(path)) return null
        return importOrganizer.organizeImports(IosVirtualFile(path), text)
    }

    fun close() {
        lastByFile.clear()
        cachesBySnapshot.clear()
        service.close()
        index?.close()
    }

    private fun isKotlin(path: String): Boolean = path.endsWith(".kt", ignoreCase = true)

    private companion object {
        /**
         * The style a reformat here applies: Kotlin official (4-space indent, 8-space continuation), which is
         * what `CodeStyleSettings.default("kotlin")` resolves to on the JVM hosts, with their defaults for
         * every spacing knob the Kotlin formatter reads.
         *
         * A constant rather than a preference read because there is nothing to read yet: the Code Style
         * screen is on screen here (it is shared UI) but `SettingsService.setCodeStyle` is still one of the
         * members this host inherits unimplemented, so nothing it edits is ever persisted. When that is
         * wired, this becomes a lookup and the value stays the default.
         */
        val FORMAT_STYLE = FormatStyle(
            styleId = "kotlin_official",
            indentSize = 4,
            continuationIndent = 8,
            tabWidth = 4,
        )

        /**
         * What a Kotlin editor asks a classpath for.
         *
         * `kotlin.callables` is the one that answers `println`; `kotlin.typeShape` a library type's members;
         * the two `builtins` ones the types that live only in `.kotlin_builtins` and have no class file at
         * all. Java's own class-name and member indexes are not here because this host has no Java backend
         * to ask them.
         */
        val INDEXES = listOf(
            KotlinCallableIndex,
            KotlinTypeShapeIndex,
            KotlinBuiltinsIndex,
            KotlinBuiltinCallableIndex,
        )
    }
}

/** A [VirtualFile] over this host's file system, which is what the symbol service walks a source tree with. */
internal class IosVirtualFile(override val path: String) : VirtualFile {
    override val name: String get() = IosFiles.nameOf(path)
    override val isDirectory: Boolean get() = IosFiles.isDirectory(path)
    override val exists: Boolean get() = IosFiles.exists(path)
    override val length: Long get() = IosFiles.size(path)

    override fun parent(): VirtualFile? = IosFiles.parentOf(path)?.let { IosVirtualFile(it) }

    override fun children(): List<VirtualFile> =
        if (!isDirectory) emptyList() else IosFiles.list(path).map { IosVirtualFile(IosFiles.join(path, it)) }

    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = IosFiles.readBytes(path) ?: ByteArray(0)
    override fun readText(): CharSequence = IosFiles.readText(path)
}

/** The live editor buffer as the analysis sees it. */
private class IosDocument(private val content: String, override val file: VirtualFile) : DocumentSnapshot {
    override val version: Long = 1
    override val text: CharSequence get() = content
    override fun length(): Int = content.length
}
