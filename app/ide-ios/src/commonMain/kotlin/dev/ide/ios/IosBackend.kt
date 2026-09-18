package dev.ide.ios

import dev.ide.kotlin.syntax.KotlinOutline
import dev.ide.ios.store.IosPreferences
import dev.ide.ios.store.IosStoreService
import dev.ide.ios.store.StoreConfig
import dev.ide.deps.ArtifactKind
import dev.ide.lang.kotlin.NavKind
import dev.ide.model.Coordinate
import dev.ide.platform.ConcurrentMap
import dev.ide.platform.ProgressReporter
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionEdits
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiArtifactSearch
import dev.ide.ui.backend.UiCachedVersion
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDefinition
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiNavKind
import dev.ide.ui.backend.UiNavOption
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiRepository
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiTextRange
import dev.ide.ui.backend.UiVersionConflict
import dev.ide.lang.dom.Severity
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.backend.UiFileSymbol
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.icons.fileIconId
import dev.ide.ui.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The iOS host's [dev.ide.ui.backend.IdeBackend]: real files, real projects, a real Kotlin editor.
 *
 * It extends [StubBackend] rather than implementing the port from scratch, so every concern this host still
 * has no answer for keeps the empty/`Unsupported` behaviour the UI already renders around, and this class is
 * only the part that is genuinely implemented. What that leaves out is now a short list, and one theme runs
 * through it: **there is no build here.** Compilation, running, signing, packaging and the SDK manager all
 * bottom out in JVM code (the Kotlin compiler, JDT, ASM, D8) with no Kotlin/Native counterpart, and the
 * project model that would describe a build has not crossed either — so module settings, source sets and
 * facets stay unanswered, and a project here is a directory of Kotlin files rather than a module graph.
 *
 * What DOES work is the editor, end to end:
 *
 *  * Syntax — highlighting, bracket matching, indent — from `commonMain`'s lexical layer, with no backend
 *    involvement at all; outline and folding from `:kotlin-syntax`, the Kotlin compiler's own parser
 *    vendored and built for this target.
 *  * Completion and diagnostics from `:lang-kotlin`'s editor half, which is the SAME symbol table, resolver
 *    and inference the desktop and Android hosts run. See [IosKotlinAnalysis].
 *  * Inlay hints, parameter info, type-aware COLORING, Reformat and Optimize Imports off that same half.
 *    Coloring was the last pass missing here, and for a compiler reason rather than a portability one --
 *    see [IosKotlinAnalysis.highlighting].
 *  * A library classpath, resolved from Maven by `:deps-impl` and indexed by `:index-impl`, so library types
 *    and callables are not merely resolvable but discoverable. See [IosDependencies].
 *  * Dependency management over that same resolver: declare, remove, search, pick a version, and see the new
 *    library in completion without reopening the project.
 *  * Find-in-files and go-to-symbol over the open project, both by walking it rather than indexing it. See
 *    [IosSearch] for why that is the right shape here and not a shortcut.
 */
class IosBackend(
    /**
     * Where projects live. Defaults to the app's Documents container (see [IosFiles] for why there is no
     * alternative on iOS); a test passes a temporary directory so it never touches real user data.
     */
    private val projectsRoot: String = IosFiles.join(IosFiles.documentsDir(), "Projects"),
    /**
     * The app-global key/value store behind [preference]. Defaults to `NSUserDefaults.standard`; a test
     * passes a private suite so it never writes into the defaults of whatever process runs it.
     */
    private val preferences: IosPreferences = IosPreferences(),
) : StubBackend() {

    /**
     * How this host reads a project's dependencies and turns them into a classpath.
     *
     * A settable property rather than a constructor parameter because `IosDependencies` is internal and this
     * class is the app's public entry point. Injected for one reason: resolution downloads, and a test must
     * be able to drive the whole path against a fixture without opening a socket.
     */
    internal var dependenciesFor: (String) -> IosDependencies = { IosDependencies(it) }

    private var active: ProjectInfo? = null

    /**
     * Project creation and the project model behind it: the built-in templates, and what a created project
     * records about itself. See [IosProjects] for why the store is opened per operation rather than held.
     */
    private val projectModel = IosProjects()

    /**
     * The Projects Store: browse, install, sign in, review, moderate.
     *
     * The transport and the mapping onto the UI's contract are the shared `:store-impl` / `:store-bridge`
     * code every host runs. What this host supplies is where things go — projects into the Documents
     * container the Files app exposes, working state into Application Support — and what adopting an
     * installed project means here, which is nothing beyond it being a directory: [projects] lists what is
     * under the root, so an unpacked archive IS a project the moment it lands.
     */
    override val store: StoreService = IosStoreService.supabase(
        url = StoreConfig.SUPABASE_URL,
        apiKey = StoreConfig.SUPABASE_KEY,
        // `CFBundleVersion`, which is iOS's build number and the exact counterpart of Android's
        // versionCode — the store filters items by it, so a number derived from the marketing version
        // would claim a build that does not exist and change which items the feed offers.
        appBuild = IosBundle.buildNumber(),
        projectsRoot = { projectsRoot },
        cacheRoot = IosFiles.supportDir(),
        preferences = preferences,
        adopt = { dir ->
            if (!IosFiles.isDirectory(dir)) {
                "That download isn't a project CodeAssist can open"
            } else {
                // Nothing else to do: [projects] lists the directories under the root, so an unpacked
                // archive is a project the moment it lands. The picker just has to be told to look again.
                bumpProjects()
                null
            }
        },
    )

    private val fsEpoch = MutableStateFlow(0)
    private val projEpoch = MutableStateFlow(0)

    init {
        if (!IosFiles.exists(projectsRoot)) IosFiles.mkdirs(projectsRoot)
        // The build-system migration notice speaks to users upgrading from an earlier CodeAssist, whose
        // projects the rebuilt build system cannot open. This is the first CodeAssist on iOS, so there is
        // nothing to have upgraded from and nothing to back up: acknowledge it before it is ever asked.
        if (preferences.get(MIGRATION_ACK_PREF) == null) preferences.put(MIGRATION_ACK_PREF, "true")
    }

    // ---- identity -----------------------------------------------------------------------------------

    override val project: ProjectInfo
        get() = active ?: ProjectInfo(name = "", rootPath = "", moduleCount = 0)

    // ---- FileService --------------------------------------------------------------------------------

    override val fileSystemEpoch: StateFlow<Int> = fsEpoch

    /**
     * The active project as a tree. [mode] is ignored: the curated "Project" view exists on the other hosts
     * to fold a Gradle module layout into source sets, and an iOS project is a plain folder of files, so the
     * raw tree IS the curated one.
     */
    override fun fileTree(mode: TreeViewMode): TreeNode {
        val root = active?.rootPath
        if (root == null || !IosFiles.isDirectory(root)) {
            return TreeNode(id = "root", name = "No project", kind = NodeKind.Workspace, filePath = null)
        }
        return TreeNode(
            id = root,
            name = IosFiles.nameOf(root),
            kind = NodeKind.Workspace,
            filePath = null,
            iconId = "workspace",
            children = childrenOf(root, depth = 0),
        )
    }

    /** Directories before files, each alphabetical, dot-entries hidden. */
    private fun childrenOf(dir: String, depth: Int): List<TreeNode> {
        // A symlink loop or a pathologically deep tree would otherwise recurse until the stack goes; the
        // limit is far past any real source layout.
        if (depth >= MAX_TREE_DEPTH) return emptyList()
        val entries = IosFiles.list(dir).filterNot { it.startsWith(".") }
        val (dirs, files) = entries.map { IosFiles.join(dir, it) }.partition { IosFiles.isDirectory(it) }
        return dirs.sortedBy { IosFiles.nameOf(it).lowercase() }.map { path ->
            TreeNode(
                id = path,
                name = IosFiles.nameOf(path),
                kind = NodeKind.Folder,
                filePath = null,
                iconId = "folder",
                children = childrenOf(path, depth + 1),
            )
        } + files.sortedBy { IosFiles.nameOf(it).lowercase() }.map { path ->
            val name = IosFiles.nameOf(path)
            TreeNode(
                id = path,
                name = name,
                kind = NodeKind.File,
                filePath = path,
                iconId = fileIconId(name),
            )
        }
    }

    override fun readFile(path: String): String = IosFiles.readText(path)

    override fun moduleNameForFile(path: String): String? =
        active?.takeIf { path.startsWith(it.rootPath) }?.name

    override fun createFile(dirPath: String, fileName: String, content: String): String? {
        val path = IosFiles.join(dirPath, fileName)
        if (IosFiles.exists(path)) return null
        if (!IosFiles.writeText(path, content)) return null
        bumpFs()
        return path
    }

    /** [name] may carry nested folders (`ui/screens/Home.kt`); the intermediate directories are created. */
    override fun createFileSmart(dirPath: String, name: String): String? {
        val path = IosFiles.join(dirPath, name.trim('/'))
        if (IosFiles.exists(path)) return null
        if (!IosFiles.writeText(path, "")) return null
        bumpFs()
        return path
    }

    override fun createDirectory(parentPath: String, name: String): String? {
        val path = IosFiles.join(parentPath, name.trim('/'))
        if (!IosFiles.mkdirs(path)) return null
        bumpFs()
        return path
    }

    override fun deletePath(path: String): Boolean =
        IosFiles.delete(path).also { if (it) bumpFs() }

    override fun listDirectory(dirPath: String): List<UiDirEntry> =
        IosFiles.list(dirPath).filterNot { it.startsWith(".") }.map { name ->
            val path = IosFiles.join(dirPath, name)
            val isDir = IosFiles.isDirectory(path)
            UiDirEntry(name, path, isDir, if (isDir) "folder" else fileIconId(name))
        }.sortedWith(compareByDescending<UiDirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    override fun movePath(path: String, destDir: String): String? {
        val dest = IosFiles.join(destDir, IosFiles.nameOf(path))
        if (IosFiles.exists(dest) || !IosFiles.move(path, dest)) return null
        bumpFs()
        return dest
    }

    override fun copyPath(path: String, destDir: String): String? {
        val dest = IosFiles.join(destDir, IosFiles.nameOf(path))
        if (IosFiles.exists(dest) || !IosFiles.copy(path, dest)) return null
        bumpFs()
        return dest
    }

    // ---- EditorService ------------------------------------------------------------------------------

    /**
     * The analysis for the open project, or null before one is opened.
     *
     * Rebuilt per project rather than per call: the symbol service caches the whole source model, and that is
     * what makes completion answer in milliseconds instead of re-walking the tree on every keystroke.
     */
    private var kotlin: IosKotlinAnalysis? = null

    /**
     * The one thread every piece of language state is touched on.
     *
     * The other hosts confine their engine the same way and for the same two reasons, but this host had
     * NEITHER of them until now. The symbol service, the parse cache and the resolver memos are ordinary
     * mutable state with no locking, so two passes at once would corrupt them; and the editor daemon runs its
     * passes from `rememberCoroutineScope()`, which is the MAIN thread, so before this every completion,
     * diagnostic and hint ran where the frame is drawn -- including the first one, which builds the symbol
     * model and reads every jar on the classpath.
     *
     * `limitedParallelism(1)` over the IO pool rather than `:ide-core`'s dedicated thread: that one is
     * pinned to work around a 32-bit ARM ART weak-memory bug (see `IdeServicesBackend.engineDispatcher`)
     * which has no counterpart on Kotlin/Native, and a pinned thread here would need closing for nothing in
     * return. The IO pool rather than [Dispatchers.Default] because this lane BLOCKS on files -- jars, source
     * trees, index segments -- and `Dispatchers.Default` is the CPU pool, which is the mistake the store
     * already made and fixed on this platform.
     */
    private val analysisDispatcher = ioDispatcher.limitedParallelism(1)

    /**
     * The live editor buffers, by path.
     *
     * Host state, not the analysis object's, so that recording a keystroke is a map write and nothing more.
     * It used to reach [IosKotlinAnalysis] directly, which meant opening the first file CONSTRUCTED that
     * object -- symbol model, classpath index and all -- on whatever thread the editor called from.
     */
    private val buffers = ConcurrentMap<String, String>()

    /**
     * Bumped whenever the analysis must be thrown away (the project changed, its dependencies did).
     *
     * A counter rather than a `kotlin = null` because callers are on any thread and [kotlin] belongs to
     * [analysisDispatcher]: nulling it from elsewhere would race a pass, and closing it would free an index
     * out from under a query. Bumping is safe from anywhere; the analysis thread compares and rebuilds.
     */
    private val analysisEpoch = MutableStateFlow(0)
    private var builtEpoch = 0

    private val indexState = MutableStateFlow(IndexUiStatus())

    /**
     * What the index is doing, which on this host is the CLASSPATH index and nothing else.
     *
     * It matters beyond the status chip: the editor restarts its passes when this goes from building to idle
     * (`EditorCenter`), which is exactly what a file opened before the index was ready needs -- its library
     * references resolve on the re-run. Left at [StubBackend]'s permanent "not building" default, that re-run
     * never happened and the status dialog described a host that never indexes anything.
     */
    override val indexStatus: StateFlow<IndexUiStatus> = indexState

    /**
     * The analysis for the open project, or null before one is opened.
     *
     * **Only ever called on [analysisDispatcher]**, which is what makes the unsynchronised [kotlin] and
     * [builtEpoch] safe. Rebuilt per project rather than per call: the symbol service caches a whole source
     * model, and that is what makes completion answer in milliseconds instead of re-walking the tree.
     */
    private fun analysis(): IosKotlinAnalysis? {
        val epoch = analysisEpoch.value
        if (builtEpoch != epoch) {
            kotlin?.close()
            kotlin = null
            builtEpoch = epoch
        }
        val root = active?.rootPath?.takeIf { it.isNotEmpty() } ?: return null
        kotlin?.let { return it }
        // Built from whatever jars are ALREADY on disk — never a fetch, because this runs on the completion
        // path. `ensureClasspath` is what puts them there, and resets this so the next call picks them up.
        val jars = dependenciesFor(root).cachedJars()
        indexState.value = IndexUiStatus(
            building = true,
            message = "Indexing libraries",
            phase = "Libraries",
            fraction = 0.0,
            total = jars.size,
        )
        return try {
            IosKotlinAnalysis(
                projectRoot = root,
                classpathJars = jars,
                buffers = buffers,
                onIndexProgress = { done, total ->
                    indexState.value = IndexUiStatus(
                        building = true,
                        message = "Indexing libraries",
                        phase = "Libraries",
                        fraction = if (total == 0) 1.0 else done.toDouble() / total,
                        processed = done,
                        total = total,
                    )
                },
            ).also { kotlin = it }
        } finally {
            // In `finally` so a build that throws does not leave the UI claiming to index forever.
            indexState.value = IndexUiStatus(message = "Indexed", fraction = 1.0)
        }
    }

    /**
     * Run [block] against the open project's analysis on the thread that owns it, or answer [fallback].
     *
     * The single seam every language pass goes through, so the confinement is a property of this one function
     * rather than something each override has to remember. Failure degrades to [fallback] for the reason
     * completion always has here — one unparseable file must not disable the editor for the session — but
     * cancellation is rethrown: the daemon cancels a pass on every keystroke, and swallowing that would
     * report "no results" for a question nobody was asking any more.
     */
    private suspend fun <T> withAnalysis(fallback: T, block: suspend (IosKotlinAnalysis) -> T): T =
        withContext(analysisDispatcher) {
            val analysis = analysis() ?: return@withContext fallback
            try {
                block(analysis)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                fallback
            }
        }

    /**
     * Fetch the project's library classpath if it is not cached yet, then rebuild the analysis over it.
     *
     * Called when a project is opened or created, both of which already suspend. The editor does not wait on
     * it in any meaningful sense: a cached classpath makes this a directory check, and an uncached one is a
     * single small download that a failure turns into a no-op. What it changes is whether `List` and `String`
     * resolve — see [IosDependencies].
     */
    private suspend fun ensureClasspath(root: String) {
        // On the IO pool, not the caller's thread: opening a project is called from the UI, and `ensure`
        // downloads — through `NSURLSession` waited on with a semaphore, which blocks whatever thread it is
        // given. The same reason every store call moved off `Dispatchers.Default` on this platform.
        val jars = withContext(ioDispatcher) { dependenciesFor(root).ensure() }
        if (jars.isNotEmpty()) resetAnalysis()
    }

    /** Drop the analysis when the open project changes; the next pass builds one for the new root. */
    private fun resetAnalysis() {
        analysisEpoch.update { it + 1 }
    }

    /**
     * Record a buffer. A map write and nothing else, because this runs wherever the editor runs — which is
     * the main thread — and everything it feeds happens later, on [analysisDispatcher].
     */
    override fun updateDocument(path: String, text: String) {
        buffers[path] = text
    }

    override fun onFileClosed(path: String) {
        // The buffer must not outlive the tab: kept, it would keep winning over disk for the rest of the
        // session and hide every later write to that file. The analysis drops the file's cached parse when
        // it next notices the path is gone (see `IosKotlinAnalysis.syncOverlay`).
        buffers.remove(path)
    }

    /**
     * Code completion, from the same engine the other hosts run.
     *
     * Only for a file inside the OPEN project: the symbol service is bound to that source tree, and a file
     * outside it would be completed against someone else's declarations. Failure degrades to no suggestions
     * rather than propagating — one unparseable file must not disable the popup for the session.
     */
    override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
        val empty = UiCompletionResult(emptyList(), offset, offset)
        if (!path.isKotlin()) return empty
        return withAnalysis(empty) { it.complete(path, text, offset)?.toUi() ?: empty }
    }

    /**
     * Semantic diagnostics for the live buffer — unresolved references, type mismatches, call applicability.
     *
     * Same engine, same checks as the other hosts. Degrades to none on failure for the same reason completion
     * does: a file the analysis chokes on must not put the editor into a permanently-error state.
     *
     * Worth knowing what is MISSING while this host has no classpath: a reference to a library type has
     * nothing to resolve against, so it would be reported unresolved. The checks already withhold that
     * judgement when the symbol source cannot answer, which is why this is useful rather than a wall of
     * false errors — but it is also why project-only code is where it earns its keep today.
     */
    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> {
        if (!path.isKotlin()) return emptyList()
        val diagnostics = withAnalysis(emptyList()) { it.analyze(path, text).orEmpty() }
        return diagnostics.map { d ->
            val (line, col) = lineColOf(text, d.range.start)
            UiDiagnostic(
                severity = when (d.severity) {
                    Severity.ERROR -> UiSeverity.Error
                    Severity.WARNING -> UiSeverity.Warning
                    Severity.INFO -> UiSeverity.Info
                    Severity.HINT -> UiSeverity.Hint
                },
                line = line,
                col = col,
                message = d.message,
                startOffset = d.range.start,
                endOffset = d.range.end,
            )
        }
    }

    /** Zero-based line and column of [offset], which the UI shows in the problems list. */
    private fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
        val end = offset.coerceIn(0, text.length)
        var line = 0
        var col = 0
        for (i in 0 until end) {
            if (text[i] == '\n') { line++; col = 0 } else col++
        }
        return line to col
    }

    override fun saveFile(path: String, text: String) {
        IosFiles.writeText(path, text)
    }

    /**
     * The file's outline, for the structure view and the sticky headers.
     *
     * Parsed on the caller's coroutine rather than moved to [ioDispatcher]: this is CPU work on a string, not
     * IO, and a lazy parse of a large file measures in single-digit milliseconds.
     */
    override suspend fun fileStructure(path: String, text: String): List<UiFileSymbol> {
        if (!path.isKotlin()) return emptyList()
        return KotlinOutline.symbols(text).map {
            UiFileSymbol(it.name, it.detail, it.kind, it.nameOffset, it.endOffset, it.depth)
        }
    }

    override suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> {
        if (!path.isKotlin()) return emptyList()
        return KotlinOutline.folds(text).map {
            UiFoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault)
        }
    }

    /**
     * Inlay hints for the visible window.
     *
     * The window is honored rather than ignored: inference per `val`/lambda is the cost of this pass, and
     * pruning the subtrees outside `[startOffset, endOffset)` keeps it proportional to what is on screen
     * instead of to the file. On a phone, where the visible window is a small fraction of a real file, that
     * is the difference between a hint pass and a stall.
     */
    override suspend fun hintsAt(
        path: String, text: String, startOffset: Int, endOffset: Int,
    ): List<UiInlayHint> {
        if (!path.isKotlin()) return emptyList()
        return withAnalysis(emptyList()) { analysis ->
            analysis.inlayHints(path, text, startOffset, endOffset).orEmpty().map { it.toUi() }
        }
    }

    /**
     * Type-aware coloring for the buffer.
     *
     * Empty rather than null on a miss, and that matters: the UI overlays these on the lexical spans, so an
     * empty list means "nothing to add, keep what the lexer said" rather than "clear the colors".
     */
    override suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> {
        if (!path.isKotlin()) return emptyList()
        return withAnalysis(emptyList()) { analysis ->
            analysis.semanticTokens(path, text).orEmpty().map { it.toUi() }
        }
    }

    /** Parameter info for the call the caret is inside. Null dismisses the panel, which is what a caret that
     *  has left the call, or a callee nothing resolves, should do. */
    override suspend fun signatureHelp(path: String, text: String, offset: Int): UiSignatureHelp? {
        if (!path.isKotlin()) return null
        return withAnalysis(null) { it.signatureHelp(path, text, offset)?.toUi() }
    }

    override suspend fun formatDocument(path: String, text: String): List<UiTextEdit> =
        formatEdits(path) { it.format(path, text) }

    override suspend fun formatRange(
        path: String, text: String, selStart: Int, selEnd: Int,
    ): List<UiTextEdit> = formatEdits(path) { it.format(path, text, selStart, selEnd) }

    override suspend fun optimizeImports(path: String, text: String): List<UiTextEdit> =
        formatEdits(path) { it.optimizeImports(path, text) }

    // ---- navigation, documentation and the code-action menu -----------------------------------------

    /**
     * Go to Declaration: the single target, when there is exactly one to go to.
     *
     * The editor calls this for a tap-and-hold and falls back to [navigationOptions] when it wants the
     * menu. A target the host cannot open is already filtered out below it, so a null here means "nothing
     * to navigate to", never "somewhere this app cannot show you".
     */
    override suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? =
        withAnalysis(null) { k ->
            k.navigationTargets(path, text, offset, NavKind.DECLARATION)
                .firstOrNull()
                ?.let { UiDefinition(it.file.path, it.offset) }
        }

    override suspend fun navigationTargets(
        path: String, text: String, offset: Int, kind: UiNavKind,
    ): List<UiNavTarget> =
        withAnalysis(emptyList()) { k -> k.navigationTargets(path, text, offset, kind.toEngine()).map { it.toUi() } }

    override suspend fun navigationOptions(path: String, text: String, offset: Int): List<UiNavOption> =
        withAnalysis(emptyList()) { k ->
            k.navigationOptions(path, text, offset).map { (kind, targets) ->
                UiNavOption(kind.toUi(), targets.map { it.toUi() })
            }
        }

    override suspend fun expandSelection(path: String, text: String, selStart: Int, selEnd: Int): UiTextRange? =
        withAnalysis(null) { k ->
            k.expandSelection(path, text, selStart, selEnd)?.let { UiTextRange(it.start, it.end) }
        }

    override suspend fun quickDocAt(path: String, text: String, offset: Int): UiQuickDoc? =
        withAnalysis(null) { k -> k.quickDoc(path, text, offset)?.toUi() }

    /**
     * The code-action menu at the caret.
     *
     * Only the analysis tier: the plugin tier the JVM hosts add after it needs the plugin host, which does
     * not run here. [selEnd] is ignored because every fix this host offers is keyed to the caret rather
     * than to a selection.
     */
    override suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> =
        withAnalysis(emptyList()) { k ->
            k.codeActions(path, text, selStart).mapIndexed { i, fix -> UiAction(i, fix.title, UiActionKind.QUICK_FIX) }
        }

    /**
     * Apply the action [actionId] listed at this caret.
     *
     * The list is recomputed rather than remembered from [actionsAt]: the buffer can change between the
     * lightbulb opening and a pick, and an edit computed against older text lands at the wrong offset. An
     * id that no longer names an action yields no edits, which the editor renders as nothing happening.
     *
     * [UiActionEdits.others] stays empty: every fix here edits the file it was invoked in.
     */
    override suspend fun applyAction(
        path: String, text: String, selStart: Int, selEnd: Int, actionId: Int,
    ): UiActionEdits = withAnalysis(UiActionEdits()) { k ->
        val fix = k.codeActions(path, text, selStart).getOrNull(actionId) ?: return@withAnalysis UiActionEdits()
        UiActionEdits(focal = fix.edits.map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) })
    }

    /**
     * The three text-driven editor commands, which share a shape: Kotlin only, degrade to no edits, and
     * return a `[start, end)` replacement the editor applies through its surgical edit path.
     *
     * Degrading to no edits is right where degrading to an empty ANSWER would not be: an empty edit list says
     * "already formatted", which is exactly what the caller should do with a buffer the formatter could not
     * parse. Nothing is rewritten on a guess.
     */
    private suspend fun formatEdits(
        path: String,
        edits: suspend (IosKotlinAnalysis) -> List<DocumentEdit>?,
    ): List<UiTextEdit> {
        if (!path.isKotlin()) return emptyList()
        return withAnalysis(emptyList()) { edits(it).orEmpty().map { edit -> edit.toUi() } }
    }

    /**
     * Only Kotlin, deliberately. The other hosts answer for Java and XML too, through backends that do not
     * exist here; returning nothing is honest, where guessing would put a wrong outline on a `.java` file.
     */
    private fun String.isKotlin(): Boolean = endsWith(".kt") || endsWith(".kts")

    // ---- SearchService ------------------------------------------------------------------------------

    /**
     * Search over the open project, or null before one is opened.
     *
     * Reads the host's live buffers directly, so a search never constructs the analysis object: building
     * the symbol model and the classpath index is not something typing in a search field should trigger.
     */
    private fun search(): IosSearch? {
        val root = active?.rootPath?.takeIf { it.isNotEmpty() } ?: return null
        return IosSearch(root) { path -> buffers[path] }
    }

    /** Find-in-files. Moved off the caller's dispatcher: it walks the project and reads every file in it. */
    override suspend fun findInFiles(
        query: String, options: UiSearchOptions, limit: Int,
    ): List<UiTextMatch> {
        val search = search() ?: return emptyList()
        return withContext(ioDispatcher) { search.findInFiles(query, options, limit) }
    }

    /** Go-to-symbol over the project's own declarations. Off the caller's dispatcher for the same reason. */
    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> {
        val search = search() ?: return emptyList()
        return withContext(ioDispatcher) { search.searchSymbols(query, limit) }
    }

    /**
     * Member search stays unanswered, and the Members tab stays empty.
     *
     * It is a search for the TYPES that declare a member of a given name, which is an inverted lookup the
     * indexes this host builds cannot serve: `kotlin.callables` is keyed by callable name (it answers
     * `println`, which is what completion needs) and `kotlin.typeShape` by type FQN, so "who has a member
     * called `size`" would mean decoding every type in every jar on every keystroke. The other hosts answer
     * it from a `java.membersByOwner` index built by a workspace indexer that has no counterpart here.
     *
     * Overridden rather than inherited so the reason sits with the answer: [StubBackend]'s default is the
     * same empty list, and an unimplemented member that silently agrees with a test double is how this host
     * shipped with no preferences at all (see the `IosPreferences` note in the class docs).
     */
    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()

    // ---- ProjectService -----------------------------------------------------------------------------

    override val projectEpoch: StateFlow<Int> = projEpoch

    override fun projectsRootPath(): String = projectsRoot

    override fun projects(): List<ProjectInfo> =
        IosFiles.list(projectsRoot)
            .filterNot { it.startsWith(".") }
            .map { IosFiles.join(projectsRoot, it) }
            .filter { IosFiles.isDirectory(it) }
            .map(::projectInfo)
            .sortedByDescending { it.lastOpened }

    /**
     * The built-in templates, from the same registry the other hosts read.
     *
     * This used to be one hand-written "Empty project" entry, because nothing below it could build a model.
     * Now [IosProjects] registers `KotlinConsoleAppTemplate` and `KotlinLibraryTemplate` and the gallery
     * shows what they describe.
     */
    override fun projectTemplates(): List<UiProjectTemplate> = projectModel.templates()

    override suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult {
        val rawName = args["name"].orEmpty().trim()
        if (rawName.isEmpty()) return UiProjectResult(false, "A project needs a name")
        val root = IosFiles.join(projectsRoot, IosFiles.sanitize(rawName))
        if (IosFiles.exists(root)) return UiProjectResult(false, "A project called \"$rawName\" already exists")
        if (!IosFiles.mkdirs(root)) return UiProjectResult(false, "Could not create the project folder")
        // The template writes the source tree AND the model; `.platform/workspace.json` is what makes this
        // a project rather than a folder of files, here and on every other host that later opens it.
        val generated = withContext(ioDispatcher) { projectModel.create(root, templateId, args) }
        generated.onFailure { e ->
            // Nothing half-written survives: a directory with a model the template did not finish writing
            // opens as a damaged project, which is worse than not having created one.
            IosFiles.delete(root)
            return UiProjectResult(false, e.message ?: "Could not create the project")
        }
        resetAnalysis()
        active = projectInfo(root)
        ensureClasspath(root)
        bumpProjects()
        return UiProjectResult(true, "Created $rawName", root)
    }

    override suspend fun openProject(rootPath: String): Boolean {
        if (!IosFiles.isDirectory(rootPath)) return false
        resetAnalysis()
        active = projectInfo(rootPath)
        ensureClasspath(rootPath)
        bumpProjects()
        return true
    }

    /**
     * The bytes behind an image the UI is about to draw.
     *
     * Every picture in the store goes through here — a listing's icon, a screenshot gallery, an avatar —
     * because the shared components decode bytes rather than resolve paths themselves. Unimplemented, it
     * is not an error anywhere: each one quietly draws its flat placeholder instead, which is what the
     * whole store looked like on this host until it was.
     *
     * Capped at the same 8 MB the other hosts use: this is decoded into memory on a phone, and a file
     * larger than that is not a picture the UI has any use for.
     */
    override suspend fun imageBytes(path: String): ByteArray? = withContext(ioDispatcher) {
        if (IosFiles.size(path) > MAX_PREVIEW_IMAGE_BYTES) null else IosFiles.readBytes(path)
    }

    override suspend fun deleteProject(rootPath: String): Boolean {
        if (!IosFiles.delete(rootPath)) return false
        if (active?.rootPath == rootPath) { resetAnalysis(); active = null }
        bumpProjects()
        return true
    }


    // ---- ModuleService ------------------------------------------------------------------------------

    /**
     * The open project, as the one module it is.
     *
     * A project here is a directory of Kotlin files with no build system, so there is nothing to enumerate:
     * one module, named after the project. It exists because the Modules screen is the way into the
     * Dependencies editor, and a backend that lists no modules leaves that editor unreachable regardless of
     * what it can do.
     *
     * The rest of `ModuleService` stays unimplemented. Source sets, language level, facets, build features
     * and packaging are all descriptions of a build, and this host does not have one; answering them with
     * invented values would put an editor on screen whose Save button changes nothing.
     */
    override fun configurableModules(): List<UiModuleRef> =
        active?.let { listOf(UiModuleRef(editableModuleName(it), MODULE_TYPE_DISPLAY)) } ?: emptyList()

    /**
     * What the Modules and Dependencies screens call the thing they edit.
     *
     * ONE name, even for a project whose model has several modules, because [IosDependencies] keeps a
     * single declaration file per project: there is one dependency set here, and listing a row per module
     * would promise per-module editing this host does not have. The name comes from the model when it
     * describes exactly one module (so the screen says `app`, as the tree does) and falls back to the
     * project's own name for a folder with no model — which is what a project made by an older build is.
     */
    private fun editableModuleName(project: ProjectInfo): String =
        projectModel.modules(project.rootPath).singleOrNull() ?: project.name

    // ---- DependencyService --------------------------------------------------------------------------

    private val depsProgress = MutableStateFlow(DepsResolveState())

    override val depsState: StateFlow<DepsResolveState> = depsProgress

    /** The dependency store for the open project, or null when none is open. */
    private fun deps(): IosDependencies? =
        active?.rootPath?.takeIf { it.isNotEmpty() }?.let { dependenciesFor(it) }

    override fun dependencyModules(): List<UiDepModule> {
        val deps = deps() ?: return emptyList()
        val project = active ?: return emptyList()
        return listOf(
            UiDepModule(
                name = editableModuleName(project),
                buildSystem = BUILD_SYSTEM,
                // No Android module here, so an `.aar` has nothing to unpack into and nothing to merge its
                // manifest or resources. Saying so keeps the picker from offering artifacts that would
                // resolve and then contribute only their `classes.jar`.
                acceptsAar = false,
                dependencyCount = deps.declared().size,
            ),
        )
    }

    /**
     * The full dependency picture: what is declared, and the transitive closure resolution produced.
     *
     * Resolution runs here rather than being cached in a field because it is where the screen expects the
     * work to happen, and the second call is nearly free: everything already downloaded resolves out of the
     * on-disk Maven cache with no network at all.
     *
     * When resolution fails entirely — no signal, most often — this still answers with the declared roots
     * marked unresolved, which is the honest picture and the one that keeps the retry button meaningful. It
     * is never null for the open project: a module that returns null renders as "no such module".
     */
    override suspend fun moduleDependencies(moduleName: String): UiModuleDeps? {
        val deps = deps() ?: return null
        val declarations = deps.declared()
        val declaredByGa = declarations.associateBy { "${it.coordinate.group}:${it.coordinate.name}" }

        val result = resolving { deps.resolve(it) }

        val nodes = LinkedHashMap<String, UiDependencyNode>()
        val conflictedGas = result?.conflicts?.map { it.coordinate }?.toSet().orEmpty()

        for (artifact in result?.resolved.orEmpty()) {
            val c = artifact.coordinate
            val ga = "${c.group}:${c.name}"
            val declaration = declaredByGa[ga]
            nodes[c.toString()] = UiDependencyNode(
                coordinate = c.toString(),
                group = c.group,
                name = c.name,
                version = c.version,
                kind = if (artifact.kind == ArtifactKind.AAR) UiDepKind.Aar else UiDepKind.Jar,
                declared = declaration != null,
                scope = declaration?.scope,
                inConflict = ga in conflictedGas,
                children = artifact.dependsOn.map { it.toString() },
            )
        }

        // A declared coordinate with no resolved artifact still has to appear, or removing it is impossible
        // from the screen that is meant to manage it. This is the offline case as much as the typo one.
        val declaredNodes = declarations.map { d ->
            nodes[d.coordinate.toString()] ?: UiDependencyNode(
                coordinate = d.coordinate.toString(),
                group = d.coordinate.group,
                name = d.coordinate.name,
                version = d.coordinate.version,
                kind = UiDepKind.Jar,
                declared = true,
                scope = d.scope,
            )
        }
        for (node in declaredNodes) nodes.getOrPut(node.coordinate) { node }

        return UiModuleDeps(
            moduleName = moduleName,
            buildSystem = BUILD_SYSTEM,
            acceptsAar = false,
            declared = declaredNodes,
            nodes = nodes.values.toList(),
            conflicts = result?.conflicts.orEmpty().map { UiVersionConflict(it.coordinate, it.requested, it.chosen) },
            unresolved = result?.unresolved.orEmpty().map { it.toString() },
        )
    }

    /**
     * Re-attempt every declaration, forgetting what was recorded as absent first.
     *
     * The forgetting is the point. A confirmed 404 is negative-cached for a week, so without it Retry would
     * skip the network entirely for exactly the artifacts the user is retrying — see
     * [IosDependencies.forgetAbsentArtifacts].
     */
    override suspend fun retryDependencyResolution() {
        val deps = deps() ?: return
        deps.forgetAbsentArtifacts()
        resolving { deps.resolve(it) }
        resetAnalysis()
    }

    /**
     * Declare [coordinate] and put it on the classpath.
     *
     * The declaration is written first and the resolve follows, so a failed download leaves a dependency the
     * user can see and retry rather than one that silently did not happen. On success the analysis is
     * dropped: the next completion rebuilds it over the new jar, which is what makes the library's types and
     * callables appear in the editor without reopening the project.
     */
    override suspend fun addDependency(
        moduleName: String,
        coordinate: String,
        scope: String,
        exclusions: List<String>,
        variant: String?,
    ): UiAddResult {
        val deps = deps() ?: return UiAddResult(false, "Open a project first")
        val parsed = Coordinate.parseOrNull(coordinate)
            ?: return UiAddResult(false, "\"$coordinate\" is not a group:name:version coordinate")
        if (parsed.version.isBlank()) {
            return UiAddResult(false, "${parsed.group}:${parsed.name} needs a version")
        }
        if (!deps.add(parsed, scope)) {
            return UiAddResult(false, "${parsed.group}:${parsed.name} is already a dependency")
        }

        val result = resolving { deps.resolve(it) }
        resetAnalysis()

        if (result == null) return UiAddResult(true, "Added $parsed, but it could not be downloaded")
        if (parsed in result.unresolved) return UiAddResult(true, "Added $parsed, but it resolved to nothing")
        return UiAddResult(true, "Added $parsed", result.resolved.size)
    }

    /** Undeclare a dependency. The classpath shrinks on the next analysis, which is why this resets it. */
    override fun removeDependency(moduleName: String, coordinate: String): Boolean {
        val deps = deps() ?: return false
        val parsed = Coordinate.parseOrNull(coordinate) ?: return false
        if (!deps.remove(parsed)) return false
        resetAnalysis()
        return true
    }

    /** Change a declared dependency's version or scope: one declaration out, one in, then re-resolve. */
    override suspend fun updateDependency(
        moduleName: String,
        coordinate: String,
        version: String,
        scope: String,
        exclusions: List<String>,
    ): UiAddResult {
        val parsed = Coordinate.parseOrNull(coordinate)
            ?: return UiAddResult(false, "\"$coordinate\" is not a coordinate")
        removeDependency(moduleName, coordinate)
        return addDependency(moduleName, parsed.copy(version = version).toString(), scope, exclusions)
    }

    /**
     * Repository search for the add picker.
     *
     * [UiArtifactSearch.indexUnavailable] is the reason this exists alongside `searchArtifacts`: a phone with
     * no signal returns the same empty list as a query for an artifact that does not exist, and telling the
     * user their artifact is not real when nothing was ever reachable is worse than saying nothing.
     */
    override suspend fun artifactSearch(query: String, moduleName: String): UiArtifactSearch {
        val deps = deps() ?: return UiArtifactSearch(emptyList(), indexUnavailable = true)
        val found = deps.search(query)
        return UiArtifactSearch(
            hits = found.hits.map { UiArtifactHit(it.coordinate.toString(), it.packaging, compatible = true) },
            indexUnavailable = found.indexUnavailable,
        )
    }

    override suspend fun availableVersions(moduleName: String, coordinate: String): List<String> {
        val deps = deps() ?: return emptyList()
        val parsed = Coordinate.parseOrNull(coordinate) ?: return emptyList()
        return deps.availableVersions(parsed.group, parsed.name)
    }

    override suspend fun cachedVersions(group: String, name: String): List<UiCachedVersion> =
        deps()?.cachedVersions(group, name).orEmpty().map { (version, bytes) -> UiCachedVersion(version, bytes) }

    override suspend fun deleteCachedVersion(group: String, name: String, version: String): Boolean {
        val deleted = deps()?.deleteCachedVersion(group, name, version) ?: false
        // A deleted jar is one the analysis may still be holding open through its index. Drop it, so the
        // next completion is built over what is actually on disk.
        if (deleted) resetAnalysis()
        return deleted
    }

    /**
     * Where libraries resolve from. Both are built in and neither can be removed, because a user-added
     * repository would have to persist somewhere and this host has no project model to persist it in — the
     * same reason declarations live in a file of their own.
     */
    override fun repositories(): List<UiRepository> =
        IosDependencies.REPOSITORIES.map { UiRepository(it.name, it.url, builtin = true) }

    /**
     * Run [block] with a progress reporter wired to [depsState], so the screen's resolve bar moves.
     *
     * The flow is reset to idle in a `finally`: a resolve that throws must not leave a spinner running for
     * the rest of the session.
     */
    private suspend fun <T> resolving(block: suspend (ProgressReporter) -> T): T {
        depsProgress.value = DepsResolveState(resolving = true, message = "Resolving dependencies", fraction = -1.0)
        try {
            return block(
                object : ProgressReporter {
                    override fun report(fraction: Double, message: String?) {
                        val state = depsProgress.value
                        depsProgress.value = state.copy(
                            fraction = fraction,
                            message = message ?: state.message,
                            log = if (message == null) state.log else (state.log + message).takeLast(MAX_RESOLVE_LOG),
                        )
                    }

                    override fun checkCanceled() = Unit
                    override val isCanceled: Boolean get() = false
                },
            )
        } finally {
            depsProgress.value = DepsResolveState()
        }
    }

    // ---- SettingsService ----------------------------------------------------------------------------

    /**
     * App-global preferences, in `NSUserDefaults`.
     *
     * [StubBackend] answers null here and drops every write, which is right for a test double and wrong for
     * a host: these are the flags that remember a first launch happened. Without them the onboarding tour
     * and the migration notice re-read "never seen" on every cold start and the picker opens under a stack
     * of sheets, and the resumed project is forgotten between runs.
     *
     * `NSUserDefaults` is the store for exactly this kind of small durable value; a credential goes to the
     * keychain instead (see [dev.ide.ios.store.IosTokenStore]).
     */
    override fun preference(key: String): String? = preferences.get(key)

    override fun setPreference(key: String, value: String) = preferences.put(key, value)

    // ---- internals ----------------------------------------------------------------------------------

    private fun bumpFs() { fsEpoch.value += 1 }

    /** A project change moves files too, so the tree is re-read alongside the project switch. */
    private fun bumpProjects() { projEpoch.value += 1; bumpFs() }

    /**
     * A project as the picker shows it, with the module count read from the model.
     *
     * One place rather than three, because the count is the part that used to be a lie: it was hard-coded
     * to 1, so a two-module project created on the desktop and opened here described itself as one.
     */
    private fun projectInfo(root: String) = ProjectInfo(
        name = IosFiles.nameOf(root),
        rootPath = root,
        moduleCount = projectModel.modules(root).size.coerceAtLeast(1),
        lastOpened = IosFiles.modifiedMs(root),
    )

    private companion object {
        const val MAX_TREE_DEPTH = 12

        /** What the one module this host has is called on screen, and the build system behind it: none. */
        const val MODULE_TYPE_DISPLAY = "Kotlin"
        const val BUILD_SYSTEM = "none"

        /** How much resolve chatter the editor's expandable log keeps. Bounded: it runs on a phone. */
        const val MAX_RESOLVE_LOG = 200

        /** `CodeAssistAppState`'s flag for the build-system migration notice; see the seeding in `init`. */
        const val MIGRATION_ACK_PREF = "migration.acknowledged"

        /** The same ceiling the other hosts decode up to; see [imageBytes]. */
        const val MAX_PREVIEW_IMAGE_BYTES = 8L * 1024 * 1024
    }
}
