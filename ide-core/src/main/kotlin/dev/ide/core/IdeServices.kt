package dev.ide.core

import dev.ide.lang.AnalysisResult
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.TextRange
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.LanguageId
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.QUICK_FIX_PROVIDER_EP
import dev.ide.analysis.WorkspaceEdit
import dev.ide.analysis.impl.AnalysisEngine
import dev.ide.analysis.impl.AnalysisEnvironment
import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.BlockEdit
import dev.ide.block.BlockTree
import dev.ide.block.impl.BlockProjectionEngine
import dev.ide.block.impl.JavaBlockMapping
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.index.INDEX_EP
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidVariants
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.TaskGraph
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.DexBackend
import dev.ide.build.engine.DexResult
import dev.ide.build.engine.DexRunner
import dev.ide.build.engine.GuardCategory
import dev.ide.build.engine.Guards
import dev.ide.build.engine.PermissionBroker
import dev.ide.build.engine.JavaBuildSystem
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaCompileResult
import dev.ide.build.engine.KotlinCompile
import dev.ide.build.engine.KotlinCompileResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskStatus
import dev.ide.index.MemberValue
import dev.ide.index.SymbolValue
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.jdt.JdtLanguageBackend
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.lang.xml.XmlSourceAnalyzer
import dev.ide.lang.jdt.context.ModuleCompilationContext
import dev.ide.lang.jdt.index.JavaClassNamesIndex
import dev.ide.lang.jdt.index.JavaMembersByOwnerIndex
import dev.ide.lang.jdt.index.JavaMembersIndex
import dev.ide.lang.jdt.index.JavaPackageTypesIndex
import dev.ide.lang.jdt.index.JavaPackagesIndex
import dev.ide.lang.jdt.index.JavaSourceSymbolsIndex
import dev.ide.model.ContentRole
import dev.ide.model.IconTarget
import dev.ide.model.Module
import dev.ide.model.impl.DefaultFileIconProvider
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.FileIconRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.model.impl.SdkData
import dev.ide.model.impl.jdk.JdkSdkProvider
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.Workspace
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateId
import dev.ide.core.templates.JavaConsoleAppTemplate
import dev.ide.core.templates.JavaLibraryTemplate
import dev.ide.core.templates.KotlinConsoleAppTemplate
import dev.ide.core.templates.KotlinLibraryTemplate
import dev.ide.platform.Disposable
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.impl.DEFAULT_REPOSITORIES
import dev.ide.deps.impl.MavenDependencyResolver
import dev.ide.deps.impl.ResolverCache
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.SdkDependency
import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.ColorEntry
import dev.ide.android.support.preview.ColorResources
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttrEntry
import dev.ide.android.support.metadata.AttrsXmlParser
import dev.ide.android.support.metadata.SdkMetadataCodec
import dev.ide.android.support.metadata.StyleableEntry
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.lang.dom.Severity
import dev.ide.lang.jdt.synthetic.SyntheticJavaSource
import dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.platform.ProgressReporter
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.backend.UiVersionConflict
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The UI-agnostic façade that wires the whole framework together: platform-core (extension registry,
 * message bus, model lock, activities), project-model-impl (the open workspace + its modules, SDK,
 * persistence), and lang-jdt (per-module analyzers + completion). The UI talks only to this.
 *
 * Each module gets one cached [SourceAnalyzer] built from a model-derived [ModuleCompilationContext],
 * so completion resolves the module's own code, the modules/projects it depends on, and the platform
 * (the workspace SDK) — all without touching the host process.
 */
/**
 * On-device Android build inputs supplied by the launcher (null on desktop): the bundled `android.jar`
 * (boot classpath + aapt2 `-I`), the app's `nativeLibraryDir` holding the extracted per-ABI
 * `libaapt2.so`/`libzipalign.so` prebuilts, and the debug keystore copied out of assets. Drives the
 * in-process Android build wiring in [IdeServices.androidBuild].
 */
class AndroidDeviceTools(
    val androidJar: Path,
    val nativeLibDir: Path,
    val debugKeystore: Path,
    /** The running device's API level (`Build.VERSION.SDK_INT`): the min-api the ephemeral Java dex-run
     *  targets, so D8 desugars only what this device needs. */
    val apiLevel: Int = 21,
)

/**
 * Installs (and then launches) a freshly built APK: the on-device "Run" for an android-app. Supplied by
 * :ide-android (it needs Android's `PackageInstaller` + the OS install-confirmation UI); null on the
 * desktop, where the android task stops at producing the signed artifact. [installAndLaunch] returns once
 * the install is initiated and streams progress + the eventual launch to [log].
 */
interface ApkInstaller {
    suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean
}

class IdeServices private constructor(
    val platform: PlatformCore,
    val store: ProjectModelStore,
    /** Non-null only on-device; selects the in-process Android build over desktop SDK detection. */
    private val androidTools: AndroidDeviceTools? = null,
    /** Non-null only on-device (supplied by :ide-android): runs a dexed console app via `DexClassLoader`,
     *  so a Java `run` works on ART where forking `java` is not possible. */
    private val dexRunner: DexRunner? = null,
    /** Non-null only on-device (supplied by :ide-android): installs + launches a built APK (the android Run). */
    private val apkInstaller: ApkInstaller? = null,
) : AutoCloseable {

    // Language backends are contributed through the `platform.languageBackend` EP and selected per file by
    // matching the file's LanguageId against each backend's `languages`, so adding a language is one more
    // registration rather than a host edit. Java (JDT) is registered first as the default.
    private val languageBackends: List<LanguageBackend> = run {
        val plugin = PluginId("language-backends")
        platform.extensions.register(LANGUAGE_BACKEND_EP, JdtLanguageBackend(), plugin)
        platform.extensions.register(LANGUAGE_BACKEND_EP, XmlLanguageBackend(), plugin)
        platform.extensions.register(LANGUAGE_BACKEND_EP, KotlinLanguageBackend(), plugin)
        platform.extensions.extensions(LANGUAGE_BACKEND_EP)
    }

    /** The backend whose `languages` contains [language], or the first (Java/JDT) as a fallback. */
    private fun backendFor(language: LanguageId): LanguageBackend =
        languageBackends.firstOrNull { language in it.languages } ?: languageBackends.first()

    /** The language of [file] by extension: `.xml` → xml, `.kt` → kotlin, everything else → java (default). */
    private fun languageFor(file: Path): LanguageId {
        val name = file.fileName?.toString() ?: return LanguageId("java")
        return when {
            name.endsWith(".xml") -> XmlLanguageBackend.LANGUAGE_ID
            name.endsWith(".kt") || name.endsWith(".kts") -> KotlinLanguageBackend.LANGUAGE_ID
            else -> LanguageId("java")
        }
    }

    private fun isKotlin(file: Path): Boolean =
        file.fileName?.toString()?.let { it.endsWith(".kt") || it.endsWith(".kts") } == true

    private val analyzers = ConcurrentHashMap<String, SourceAnalyzer>()
    private val docVersion = AtomicLong(0)

    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The workspace index (class names, packages, members, source symbols). Built in the background. */
    val indexService: IndexService = run {
        val plugin = PluginId("indexing")
        listOf(
            JavaClassNamesIndex, JavaPackagesIndex, JavaPackageTypesIndex, JavaSourceSymbolsIndex, JavaMembersIndex,
            JavaMembersByOwnerIndex, // cross-language: a Kotlin file enumerating a Java SOURCE class's members
            dev.ide.lang.kotlin.index.KotlinTypeShapeIndex, // Kotlin backend: persistent owner-keyed member shapes
            AndroidResourceIndex, // Android resource declarations
        ).forEach { platform.extensions.register(INDEX_EP, it, plugin) }
        IndexServiceImpl(
            platform.extensions.extensions(INDEX_EP),
            store.rootPath.resolve(".platform/caches/index"),
            // On-device (androidTools present) ART has a tight heap, so use a smaller hot-block cache; desktop keeps the default.
            blockCacheBytes = if (androidTools != null) IndexServiceImpl.CONSTRAINED_BLOCK_CACHE_BYTES
            else IndexServiceImpl.DEFAULT_BLOCK_CACHE_BYTES,
        )
    }

    /** Resolves a tree node to an icon id via the registered `platform.fileIcon` providers. */
    val fileIcons: FileIconRegistry = FileIconRegistry(platform.extensions)

    /** The icon id for [target] (default classifier + plugins), or null if no provider is registered. */
    fun iconFor(target: IconTarget): String? = fileIcons.resolve(target)

    private val _indexStatus = MutableStateFlow(IndexUiStatus())
    val indexStatus: StateFlow<IndexUiStatus> get() = _indexStatus

    init {
        // Provision kotlin-stdlib as a real project dependency (bundled jar, never the host runtime) before
        // anything reads a Kotlin module's classpath.
        runCatching { ensureKotlinStdlib() }
        indexService.observeStatus { s -> _indexStatus.value = IndexUiStatus(s.building, s.message, s.fraction) }
        indexScope.launch { runCatching { indexService.ensureUpToDate(buildIndexScope()) } }
    }

    private fun buildIndexScope(): IndexScope {
        val jdt = modules().map { analyzerFor(it) }.filterIsInstance<JdtSourceAnalyzer>()
        return IndexScope(
            sourceRoots = jdt.flatMap { it.sourceRootPaths }.distinct(),
            libraryJars = jdt.flatMap { it.classpathJarPaths }.distinct(),
            jdkHome = jdt.firstNotNullOfOrNull { it.jdkHome },
            // Index res/ XML: project + dependency-module + AAR res (the same merge the repository sees).
            resourceRoots = modules().flatMap { m ->
                if (m.facets.get(dev.ide.android.support.AndroidFacet.KEY) != null)
                    runCatching { AndroidResources.resourceDirs(m, store.workspace) }.getOrDefault(emptyList())
                else resourceRoots(m)
            }.distinct(),
        )
    }

    /** Go-to-symbol over project declarations (navigable). */
    fun searchSymbols(query: String, limit: Int = 50): List<SymbolValue> =
        indexService.fuzzy<SymbolValue>(IndexId("java.sourceSymbols"), query, limit).map { it.value }.toList()

    /** Member search across the classpath (informational). */
    fun searchMembers(query: String, limit: Int = 50): List<MemberValue> =
        indexService.fuzzy<MemberValue>(IndexId("java.members"), query, limit).map { it.value }.toList()

    /**
     * Full-text find-in-files over every surfaced workspace file (code, resources, assets). Reads the live
     * editor overlay when a file is open (so unsaved edits are searched), else disk. Skips binary/oversized
     * files. Returns up to [limit] matches.
     */
    fun findInFiles(query: String, options: UiSearchOptions, limit: Int): List<UiTextMatch> {
        if (query.isBlank()) return emptyList()
        val regex = buildSearchRegex(query, options) ?: return emptyList()
        val out = ArrayList<UiTextMatch>()
        val seen = HashSet<String>()
        for (module in modules()) {
            for (root in treeRoots(module)) {
                if (!Files.isDirectory(root)) continue
                val files = runCatching {
                    Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                }.getOrDefault(emptyList())
                for (file in files) {
                    val abs = file.toAbsolutePath().normalize()
                    if (!seen.add(abs.toString())) continue
                    if (isLikelyBinary(abs)) continue
                    val text = openDocuments[abs] ?: runCatching { file.readText() }.getOrNull() ?: continue
                    if (text.length > MAX_SEARCH_FILE_CHARS || text.any { it.code == 0 }) continue
                    val name = file.fileName.toString()
                    var lineStart = 0
                    var lineNo = 1
                    var i = 0
                    val n = text.length
                    while (i <= n) {
                        if (i == n || text[i] == '\n') {
                            val line = text.substring(lineStart, i)
                            for (m in regex.findAll(line)) {
                                if (m.range.isEmpty()) continue
                                out += UiTextMatch(
                                    filePath = abs.toString(), fileName = name,
                                    line = lineNo, col = m.range.first + 1, lineText = line,
                                    matchStart = m.range.first, matchEnd = m.range.last + 1,
                                    offset = lineStart + m.range.first,
                                )
                                if (out.size >= limit) return out
                            }
                            lineStart = i + 1
                            lineNo++
                        }
                        i++
                    }
                }
            }
        }
        return out
    }

    private fun buildSearchRegex(query: String, options: UiSearchOptions): Regex? {
        val core = when {
            options.regex -> query
            options.wholeWord -> "\\b" + Regex.escape(query) + "\\b"
            else -> Regex.escape(query)
        }
        val opts = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(core, opts) }.getOrNull()
    }

    private fun isLikelyBinary(path: Path): Boolean {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    /** Re-invalidate and rebuild the workspace indexes from scratch (the UI's "Re-index" action). */
    fun reindex() {
        invalidateSyntheticClasses()
        indexScope.launch {
            runCatching {
                indexService.invalidate()
                indexService.ensureUpToDate(buildIndexScope())
            }
        }
    }

    /**
     * Re-sync the index to the current model WITHOUT the destructive full rebuild [reindex] does: [ensureUpToDate]
     * reopens unchanged artifacts' on-disk segments (cheap) and builds only new/changed ones, while segments for
     * removed dependencies fall out. Used for automatic model-change triggers — on device this is the difference
     * between reopening the android.jar segment and re-scanning its ~40k classes on every dependency/facet edit.
     */
    private fun resyncIndex() {
        invalidateSyntheticClasses()
        indexScope.launch { runCatching { indexService.ensureUpToDate(buildIndexScope()) } }
    }

    // ---- SDK / toolchain manager (download Android SDK packages + JDK sources) ----

    /** Downloads Android SDK packages + JDK sources behind a progress flow. Invalidates analyzers on change. */
    val sdkManager = SdkManagerService(store.rootPath, onChanged = ::invalidateAnalyzers)

    // ---- Android platform sources (for parameter names + javadoc on android.* APIs) ----

    /** Status of the Android platform sources for inlay/completion docs, or null when there's no Android SDK. */
    fun androidSourcesInfo(): AndroidSourcesInfo? {
        val sdkRoot = dev.ide.android.support.tools.AndroidSdk.findSdkRoot(workspaceRoot) ?: return null
        val sdk = dev.ide.android.support.tools.AndroidSdk.detect(sdkRoot) ?: return null
        val platform = sdk.androidJar.parent?.fileName?.toString() ?: return null // android-NN
        val installed = java.nio.file.Files.isDirectory(sdkRoot.resolve("sources").resolve(platform))
        return AndroidSourcesInfo(platform, installed, downloadable = findSdkmanager(sdkRoot) != null)
    }

    /**
     * Download the Android platform sources via `sdkmanager` (desktop only). Pipes license acceptance and
     * bounds the run with a timeout so it cannot hang the IDE. On success the analyzers are invalidated so
     * the new sources take effect. Returns a human-readable status.
     */
    fun downloadAndroidSources(): String {
        val sdkRoot = dev.ide.android.support.tools.AndroidSdk.findSdkRoot(workspaceRoot) ?: return "No Android SDK found."
        val sdk = dev.ide.android.support.tools.AndroidSdk.detect(sdkRoot) ?: return "No installed Android platform."
        val platform = sdk.androidJar.parent?.fileName?.toString() ?: return "Couldn't determine the platform."
        if (java.nio.file.Files.isDirectory(sdkRoot.resolve("sources").resolve(platform))) return "Sources for $platform are already installed."
        val sdkmanager = findSdkmanager(sdkRoot) ?: return "sdkmanager not found — install the sources via Android Studio's SDK Manager (SDK Platforms → Sources for Android $platform)."
        return runCatching {
            val proc = ProcessBuilder(sdkmanager.toString(), "sources;$platform")
                .directory(sdkRoot.toFile())
                .redirectErrorStream(true)
                .start()
            proc.outputStream.bufferedWriter().use { w -> repeat(50) { runCatching { w.write("y\n"); w.flush() } } }
            val done = proc.waitFor(4, java.util.concurrent.TimeUnit.MINUTES)
            if (!done) { proc.destroyForcibly(); return "Timed out downloading sources for $platform." }
            if (proc.exitValue() == 0) {
                invalidateAnalyzers() // pick up the freshly-installed sources
                "Installed sources for $platform."
            } else "sdkmanager failed (exit ${proc.exitValue()}) installing sources for $platform."
        }.getOrElse { "Couldn't run sdkmanager: ${it.message}" }
    }

    /** Locate `sdkmanager` under the SDK (cmdline-tools preferred, then legacy tools). */
    private fun findSdkmanager(sdkRoot: java.nio.file.Path): java.nio.file.Path? {
        val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val exe = if (isWin) "sdkmanager.bat" else "sdkmanager"
        val candidates = buildList {
            add(sdkRoot.resolve("cmdline-tools").resolve("latest").resolve("bin").resolve(exe))
            val cmdlineToolsDir = sdkRoot.resolve("cmdline-tools")
            if (java.nio.file.Files.isDirectory(cmdlineToolsDir)) {
                java.nio.file.Files.list(cmdlineToolsDir).use { s -> s.forEach { add(it.resolve("bin").resolve(exe)) } }
            }
            add(sdkRoot.resolve("tools").resolve("bin").resolve(exe))
        }
        return candidates.firstOrNull { java.nio.file.Files.isRegularFile(it) }
    }

    // ---- build & run ----

    /**
     * The compile bootclasspath. On-device ([androidTools] non-null) ecj has no readable JRE (ART's platform
     * lives in `.oat`/dex it cannot load), so every compile, even a plain `java-lib` whose own classpath
     * carries no `android.jar`, must be handed the bundled `android.jar` as the platform library, or ecj
     * fails with `"java.lang.Object cannot be resolved … indirectly referenced from required .class files."`
     * On the desktop this is empty and ecj keeps using the host JDK's platform classes (so the desktop Java
     * build still targets the real JRE, not android.jar).
     */
    private val compileBootClasspath: List<Path> = listOfNotNull(androidTools?.androidJar)
    private val javaCompile = JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level, bootClasspath = compileBootClasspath)
        JavaCompileResult(r.success, r.messages)
    }

    /**
     * The Kotlin compile backend: K2 in-process (`:lang-kotlin`'s [KotlinJvmCompiler]). The same boot
     * classpath the Java compile uses ([compileBootClasspath]: empty on desktop → host JDK; `android.jar` on
     * ART) is threaded in, so a Kotlin module sees the right platform. Modules with no `.kt` never invoke it.
     */
    private val kotlinJvmCompiler = KotlinJvmCompiler()
    private val incrementalKotlin = IncrementalKotlinCompiler(kotlinJvmCompiler)
    private val kotlinCompile = KotlinCompile { kotlinSources, javaSources, classpath, out, jvmTarget ->
        val r = incrementalKotlin.compile(kotlinSources, javaSources, classpath, out, jvmTarget, bootClasspath = compileBootClasspath)
        KotlinCompileResult(r.success, r.messages)
    }
    private val buildSystem = JavaBuildSystem(javaCompile, kotlinCompile)

    /**
     * The native Android build. On-device ([androidTools] non-null) it is the in-process wiring (D8/R8/
     * apksigner in-process, bundled native `aapt2`/`zipalign` from `nativeLibraryDir`, debug keystore from
     * assets). On the desktop it is the subprocess wiring over a detected SDK, or null when none is
     * installed (the UI then reports "install an SDK" for assemble tasks).
     */
    private val androidBuild: AndroidBuildSystem? by lazy {
        androidTools?.let { t ->
            if (!Files.exists(t.androidJar) || !Files.exists(t.nativeLibDir)) return@lazy null
            val sdk = AndroidSdk.forDevice(t.androidJar, t.nativeLibDir).takeIf { it.hasNativeTools() } ?: return@lazy null
            val signing = SigningConfig(t.debugKeystore, DebugKeystore.STORE_PASS, DebugKeystore.KEY_ALIAS, DebugKeystore.KEY_PASS)
            return@lazy AndroidBuildSystem.inProcess(javaCompile, sdk, signing, kotlinCompile)
        }
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.takeIf { it.isComplete() } ?: return@lazy null
        val signing = DebugKeystore.getOrCreate(store.rootPath.resolve(".platform/debug.ks"), sdk.keytool)
        AndroidBuildSystem.subprocess(javaCompile, sdk, signing, kotlinCompile)
    }

    /**
     * On-device only: the dex backend for the Java `run` path — D8 in-process, desugaring against the
     * bundled `android.jar`. Paired with [dexRunner], it lets a console app run on ART. Null on the desktop
     * (which forks `java` via [JavaBuildSystem.createRunGraph] instead).
     */
    private val javaDexBackend: DexBackend? = androidTools?.let { t ->
        DexBackend { inputs, minApi, outDir ->
            val r = D8InProcessDexer().dex(inputs, t.androidJar, minApi, false, outDir)
            DexResult(r.success, r.log)
        }
    }

    private val buildCache = BuildCache(store.rootPath.resolve(".platform/caches/build"))
    private val _buildState = MutableStateFlow(BuildState())
    val buildState: StateFlow<BuildState> get() = _buildState

    @Volatile private var buildCtx: SimpleTaskContext? = null
    @Volatile private var buildJob: Job? = null

    // ---- runtime permission guard (mediates instrumented code's network/file/reflection/exec; see SandboxGuard) ----

    private val _permissionRequest = MutableStateFlow<UiPermissionRequest?>(null)
    val permissionRequest: StateFlow<UiPermissionRequest?> get() = _permissionRequest

    /** Remembers run/always decisions (persisted per project); the pure, testable part of the guard. */
    private val permissionPolicy = PermissionPolicy(store.rootPath.resolve(".platform/permissions.properties"))
    private val promptLock = Any()
    @Volatile private var pendingAnswer: java.util.concurrent.ArrayBlockingQueue<UiPermissionDecision>? = null
    private val permissionIdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The broker [Guards] consults from a running program's (instrumented) code. Fast-paths already-decided
     * categories via [permissionPolicy]; otherwise blocks that program's thread on a UI prompt and applies
     * the returned decision. One prompt at a time ([promptLock] serializes concurrent program threads).
     */
    private val permissionBroker = object : PermissionBroker {
        override fun check(category: GuardCategory, detail: String): Boolean {
            permissionPolicy.decided(category)?.let { return it }
            synchronized(promptLock) {
                permissionPolicy.decided(category)?.let { return it }
                val answer = java.util.concurrent.ArrayBlockingQueue<UiPermissionDecision>(1)
                pendingAnswer = answer
                _permissionRequest.value = UiPermissionRequest(permissionIdSeq.incrementAndGet(), category.name.lowercase(), detail)
                val decision = try { answer.take() } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); UiPermissionDecision.DENY }
                _permissionRequest.value = null
                pendingAnswer = null
                return permissionPolicy.apply(category, decision)
            }
        }
    }

    /** Answer the running program's pending permission prompt (from the UI). */
    fun answerPermission(id: Int, decision: UiPermissionDecision) {
        if (_permissionRequest.value?.id == id) pendingAnswer?.offer(decision)
    }

    /** Tasks the UI's Run picker offers: a Java `run` for a runnable CLI module + Android `assemble<Variant>`. */
    fun runTasks(): List<RunTaskOption> = buildList {
        findRunnable()?.let { (m, _) -> add(RunTaskOption("run:${m.name}", "Run ${m.name}", "run")) }
        for (m in modules().filter { it.type.id == "android-app" }) {
            for (v in AndroidVariants.compute(m)) {
                val cap = v.name.replaceFirstChar { it.uppercase() }
                // On device, the android Run builds + installs + launches; on desktop it stops at assemble.
                if (apkInstaller != null) add(RunTaskOption("androidRun:${m.name}:${v.name}", "Run $cap · ${m.name}", "android"))
                else add(RunTaskOption("assemble:${m.name}:${v.name}", "assemble$cap · ${m.name}", "android"))
            }
        }
    }

    /** Run/assemble the task with [id] (from [runTasks]); streams progress into [buildState]. */
    fun runTask(id: String) {
        if (_buildState.value.status == RunStatus.Running) return
        flushOpenDocuments() // save unsaved editor buffers so the compiler sees the latest source
        runCatching { ensureKotlinStdlib() } // a newly-added .kt module needs the stdlib dep before it builds/runs
        // Graph construction + topological ordering run synchronously here; a misconfiguration (cyclic deps,
        // a duplicate task) must surface as a Failed build in the console, never crash the IDE.
        try {
            when {
                id.startsWith("run:") -> {
                    val (module, mainClass) = findRunnable() ?: return fail("No runnable main(String[]) found.")
                    val project = projectOf(module) ?: return
                    val runner = dexRunner
                    val backend = javaDexBackend
                    if (runner != null && backend != null) {
                        // On-device (ART): there is no `java` to fork, so dex the runtime classpath and run the dex,
                        // targeting this device's API level (default 21 if unknown).
                        val minApi = androidTools?.apiLevel ?: 21
                        launch(module.name, buildSystem.createDexRunGraph(project, module, mainClass, minApi, backend, runner), "> Run (dex) $mainClass")
                    } else {
                        launch(module.name, buildSystem.createRunGraph(project, module, mainClass), "> Run $mainClass")
                    }
                }
                id.startsWith("assemble:") -> {
                    val (modName, variant) = id.removePrefix("assemble:").split(":").let { it[0] to it.getOrElse(1) { "debug" } }
                    val module = modules().firstOrNull { it.name == modName } ?: return fail("No module '$modName'.")
                    val android = androidBuild
                        ?: return fail("Android SDK (platform + build-tools) not found — install one to assemble Android modules.")
                    val project = projectOf(module) ?: return
                    val graph = android.createBuildGraph(project, BuildRequest(listOf(module.id), VariantSelector(variant), BuildGoal.ASSEMBLE))
                    launch(module.name, graph, "> assemble $variant · ${module.name}")
                }
                id.startsWith("androidRun:") -> {
                    val (modName, variant) = id.removePrefix("androidRun:").split(":").let { it[0] to it.getOrElse(1) { "debug" } }
                    val module = modules().firstOrNull { it.name == modName } ?: return fail("No module '$modName'.")
                    val installer = apkInstaller ?: return fail("APK install is only available on device.")
                    val android = androidBuild ?: return fail("Android SDK not found.")
                    val pkg = module.facets.get(AndroidFacet.KEY)?.namespace ?: return fail("No Android package for '$modName'.")
                    val project = projectOf(module) ?: return
                    val graph = android.createBuildGraph(project, BuildRequest(listOf(module.id), VariantSelector(variant), BuildGoal.ASSEMBLE))
                    val apk = AndroidBuildSystem.signedApkPath(module, variant)
                    // On a successful build, install + launch (the OS shows its own install-confirmation).
                    launch(module.name, graph, "> Run $variant · ${module.name}") { log -> installer.installAndLaunch(apk, pkg, log) }
                }
                else -> fail("Unknown task: $id")
            }
        } catch (e: CyclicTaskDependencyException) {
            fail("Build configuration error — cyclic task dependency: ${e.cycle.joinToString(" → ") { it.value }}")
        } catch (e: Throwable) {
            fail("Couldn't start the build: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Run the default task (first of [runTasks]) — the plain Run button + existing callers. */
    fun runBuild() {
        val first = runTasks().firstOrNull() ?: return fail("Nothing to run or assemble in this project.")
        runTask(first.id)
    }

    /**
     * Make kotlin-stdlib a resolvable dependency of every Kotlin module, sourced from the bundled jar (never
     * the host runtime; see [BundledKotlinStdlib]). Extracts the bundled stdlib to a stable workspace path,
     * registers it once as the `kotlin-stdlib` workspace library, and adds an `implementation` dependency to
     * each module that has `.kt` sources but doesn't already declare it, so the stdlib flows onto both the
     * compile and the run/dex classpaths through the normal classpath machinery (link and run). Idempotent;
     * a no-op for Java-only projects.
     */
    private fun ensureKotlinStdlib() {
        val libName = "kotlin-stdlib"
        val kotlinModules = store.workspace.projects
            .flatMap { p -> p.modules.map { p to it } }
            .filter { (_, m) -> moduleHasKotlin(m) }
        if (kotlinModules.isEmpty()) return

        if (store.workspace.libraryTable.byName(libName) == null) {
            val jar = BundledKotlinStdlib.extractTo(store.rootPath.resolve(".platform"))
                ?: BundledKotlinStdlib.hostJar() ?: return
            store.workspace.libraryTable.create(libName).apply {
                kind = LibraryKind.JAR
                addClassesRoot(store.vfs.fileFor(jar))
                commit()
            }
        }

        var changed = false
        for ((project, modules) in kotlinModules.groupBy({ it.first }, { it.second })) {
            val missing = modules.filter { m ->
                m.dependencies.none { it is LibraryDependency && it.library.name == libName }
            }
            if (missing.isEmpty()) continue
            project.beginModification().apply {
                missing.forEach { module(it.id).addDependency(LibraryDependency(LibraryRef(libName), DependencyScope.IMPLEMENTATION)) }
                commit()
            }
            changed = true
        }
        if (changed) store.save()
    }

    /** Whether [module] carries any `.kt` source (so it needs the implicit kotlin-stdlib dependency). */
    private fun moduleHasKotlin(module: Module): Boolean =
        module.sourceSets.flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { Paths.get(it.dir.path) }
            .filter { Files.isDirectory(it) }
            .any { root -> Files.walk(root).use { s -> s.anyMatch { p -> p.toString().endsWith(".kt") } } }

    private fun fail(message: String) {
        _buildState.value = BuildState(RunStatus.Failed, "", emptyList(), listOf(message), 0)
    }

    /** Stream [graph] execution into [buildState] (shared by run + assemble). [onSuccess] (e.g. install +
     *  launch an APK) runs after a successful build, receiving the console log appender. */
    private fun launch(moduleName: String, graph: TaskGraph, header: String, onSuccess: (suspend (log: (String) -> Unit) -> Unit)? = null) {
        val order = graph.topologicalLevels().flatten().map { BuildStepUi(it.name.value, StepStatus.Pending) }
        _buildState.value = BuildState(RunStatus.Running, moduleName, order, listOf(header), 0)
        val start = System.currentTimeMillis()
        val ctx = SimpleTaskContext(log = { line -> _buildState.update { it.copy(log = it.log + line) } })
        buildCtx = ctx
        // Arm the run-time guard for this run: fresh per-run decisions + this engine's broker. Only the
        // in-process dex-run executes instrumented code that consults it; other graphs never touch it.
        permissionPolicy.resetRun(); _permissionRequest.value = null
        Guards.broker = permissionBroker
        val exec = TaskExecutorImpl(buildCache, onEvent = { name, status ->
            _buildState.update { st ->
                st.copy(steps = st.steps.map { if (it.name == name.value) it.copy(status = mapStatus(status)) else it })
            }
        })
        buildJob = indexScope.launch {
            val outcome = runCatching { exec.execute(graph, ctx, maxParallel = 2) }.getOrNull()
            Guards.broker = null
            _permissionRequest.value = null
            if (outcome?.succeeded == true && onSuccess != null) {
                runCatching { onSuccess { line -> _buildState.update { st -> st.copy(log = st.log + line) } } }
                    .onFailure { ctx.logger()("post-build step failed: ${it.message}") }
            }
            _buildState.update {
                it.copy(
                    status = if (outcome?.succeeded == true) RunStatus.Succeeded else RunStatus.Failed,
                    elapsedMs = System.currentTimeMillis() - start,
                )
            }
        }
    }

    /** Cancel an in-progress build/run. */
    fun stopBuild() {
        buildCtx?.canceled = true
        buildJob?.cancel()
        pendingAnswer?.offer(UiPermissionDecision.DENY) // unblock a program waiting on a permission prompt
        Guards.broker = null
        _permissionRequest.value = null
        _buildState.update { if (it.status == RunStatus.Running) it.copy(status = RunStatus.Failed, log = it.log + "Stopped.") else it }
    }

    private fun mapStatus(status: TaskStatus): StepStatus = when (status) {
        TaskStatus.Running -> StepStatus.Running
        TaskStatus.Succeeded -> StepStatus.Done
        TaskStatus.UpToDate -> StepStatus.UpToDate
        TaskStatus.NoSource -> StepStatus.NoSource
        TaskStatus.Failed -> StepStatus.Failed
        TaskStatus.Blocked -> StepStatus.Skipped
    }

    private fun projectOf(module: Module): Project? =
        store.workspace.projects.firstOrNull { p -> p.modules.any { it.id == module.id } }

    // ---- dependency management ----

    /**
     * The Maven resolver, caching under the workspace's `.platform/caches/resolved-deps`. Downloaded jars/
     * classes-from-aars are wrapped as the store's [VirtualFile]s so they flow straight into the model's
     * [LibraryTable] → [dev.ide.model.ClasspathSnapshot] → build + analysis, like any other library.
     */
    private val depsResolver = MavenDependencyResolver(
        cache = ResolverCache(store.rootPath),
        fileFor = { p -> store.vfs.fileFor(p) },
    )

    private val _depsState = MutableStateFlow(DepsResolveState())
    val depsState: StateFlow<DepsResolveState> get() = _depsState

    private fun depsProgress(): ProgressReporter = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {
            _depsState.update { it.copy(fraction = fraction, message = message ?: it.message) }
        }
        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    /** A module can consume an Android archive (`.aar`) iff it's an Android module — facet or type. */
    private fun acceptsAar(module: Module): Boolean =
        module.facets.get(dev.ide.android.support.AndroidFacet.KEY) != null || module.type.id.startsWith("android")

    private fun aarReason(module: Module): String =
        "Android archives (.aar) need an Android module; '${module.name}' is a ${module.type.displayName.lowercase()}"

    private fun parseCoordinate(name: String): Coordinate? =
        name.split(":").takeIf { it.size >= 3 }?.let { Coordinate(it[0], it[1], it[2]) }

    private fun findLibrary(name: String) =
        store.workspace.libraryTable.byName(name)
            ?: store.workspace.projects.firstNotNullOfOrNull { it.libraryTable.byName(name) }

    private fun scopeLabel(scope: DependencyScope): String = when (scope) {
        DependencyScope.API -> "api"
        DependencyScope.IMPLEMENTATION -> "implementation"
        DependencyScope.COMPILE_ONLY -> "compileOnly"
        DependencyScope.RUNTIME_ONLY -> "runtimeOnly"
        DependencyScope.TEST_IMPLEMENTATION -> "testImplementation"
    }

    private fun parseScope(label: String): DependencyScope = when (label.lowercase().replace("_", "").replace("-", "")) {
        "api" -> DependencyScope.API
        "compileonly" -> DependencyScope.COMPILE_ONLY
        "runtimeonly" -> DependencyScope.RUNTIME_ONLY
        "testimplementation", "test" -> DependencyScope.TEST_IMPLEMENTATION
        else -> DependencyScope.IMPLEMENTATION
    }

    /** Dependency-declaring modules + their build system + AAR compatibility (the screen's module switcher). */
    fun dependencyModules(): List<UiDepModule> = modules().map { m ->
        UiDepModule(
            name = m.name,
            buildSystem = projectOf(m)?.buildSystemId?.value ?: "native",
            acceptsAar = acceptsAar(m),
            dependencyCount = m.dependencies.count { it is LibraryDependency },
        )
    }

    /**
     * Resolve [moduleName]'s full dependency picture: declared entries, the transitive graph (from the
     * resolver's `dependsOn` edges), version conflicts, cycles, and per-artifact compatibility. Network
     * resolution is cached on disk; on failure it degrades to declared-only with everything unresolved.
     */
    suspend fun moduleDependencies(moduleName: String): UiModuleDeps? {
        val module = modules().firstOrNull { it.name == moduleName } ?: return null
        val accepts = acceptsAar(module)
        val buildSystem = projectOf(module)?.buildSystemId?.value ?: "native"

        // Declared library coordinates → resolve them together so cross-dep version conflicts surface.
        val declaredGAs = LinkedHashSet<String>()
        val scopeByGa = HashMap<String, DependencyScope>()
        val externalCoords = ArrayList<Coordinate>()
        for (entry in module.dependencies) if (entry is LibraryDependency) {
            parseCoordinate(entry.library.name)?.let { c ->
                val ga = "${c.group}:${c.name}"
                declaredGAs += ga
                scopeByGa[ga] = entry.scope
                externalCoords += c
            }
        }

        val result = if (externalCoords.isEmpty()) null else {
            _depsState.value = DepsResolveState(resolving = true, message = "Resolving ${module.name}…")
            runCatching { depsResolver.resolve(externalCoords, DEFAULT_REPOSITORIES, ConflictPolicy.NEWEST, depsProgress()) }
                .getOrNull()
                .also { _depsState.update { s -> s.copy(resolving = false) } }
        }

        val conflictGAs = result?.conflicts?.map { it.coordinate }?.toSet().orEmpty()
        val edges = LinkedHashMap<String, List<String>>()
        val nodes = LinkedHashMap<String, UiDependencyNode>()
        result?.resolved?.forEach { art ->
            val coordStr = art.coordinate.toString()
            val ga = "${art.coordinate.group}:${art.coordinate.name}"
            val kind = if (art.kind == ArtifactKind.AAR) UiDepKind.Aar else UiDepKind.Jar
            val compatible = kind != UiDepKind.Aar || accepts
            val declared = ga in declaredGAs
            edges[coordStr] = art.dependsOn.map { it.toString() }
            nodes[coordStr] = UiDependencyNode(
                coordinate = coordStr,
                group = art.coordinate.group, name = art.coordinate.name, version = art.coordinate.version,
                kind = kind,
                declared = declared,
                scope = if (declared) scopeByGa[ga]?.let(::scopeLabel) else null,
                compatible = compatible,
                incompatibleReason = if (!compatible) aarReason(module) else null,
                inConflict = ga in conflictGAs,
                children = art.dependsOn.map { it.toString() },
            )
        }

        // Declared roots, in declaration order — library coords (matched by group:name), module + sdk deps.
        val unresolved = result?.unresolved?.map { it.toString() }?.toMutableSet() ?: mutableSetOf()
        val declaredRoots = ArrayList<UiDependencyNode>()
        for (entry in module.dependencies) when (entry) {
            is LibraryDependency -> {
                val coord = parseCoordinate(entry.library.name)
                if (coord != null) {
                    val ga = "${coord.group}:${coord.name}"
                    val resolvedNode = nodes.values.firstOrNull { "${it.group}:${it.name}" == ga }
                    if (resolvedNode != null) declaredRoots += resolvedNode
                    else {
                        unresolved += entry.library.name
                        val lib = findLibrary(entry.library.name)
                        val kind = if (lib?.kind == LibraryKind.AAR) UiDepKind.Aar else UiDepKind.Jar
                        val compatible = kind != UiDepKind.Aar || accepts
                        val node = UiDependencyNode(
                            coordinate = entry.library.name, group = coord.group, name = coord.name, version = coord.version,
                            kind = kind, declared = true, scope = scopeLabel(entry.scope),
                            compatible = compatible, incompatibleReason = if (!compatible) aarReason(module) else null,
                        )
                        declaredRoots += node
                        nodes.putIfAbsent(node.coordinate, node)
                    }
                } else {
                    // A local/file-based library (non-coordinate name) — show it, no transitive resolution.
                    val lib = findLibrary(entry.library.name)
                    val kind = if (lib?.kind == LibraryKind.AAR) UiDepKind.Aar else UiDepKind.Jar
                    val compatible = kind != UiDepKind.Aar || accepts
                    val node = UiDependencyNode(
                        coordinate = entry.library.name, group = "", name = entry.library.name, version = "",
                        kind = kind, declared = true, scope = scopeLabel(entry.scope),
                        compatible = compatible, incompatibleReason = if (!compatible) aarReason(module) else null,
                    )
                    declaredRoots += node
                    nodes.putIfAbsent(node.coordinate, node)
                }
            }
            is ModuleDependency -> {
                val node = UiDependencyNode(
                    coordinate = entry.target.value, group = "", name = entry.target.value, version = "",
                    kind = UiDepKind.Module, declared = true, scope = scopeLabel(entry.scope),
                )
                declaredRoots += node
                nodes.putIfAbsent(node.coordinate, node)
            }
            is SdkDependency -> { /* the SDK is shown by the project tree, not as a managed dependency */ }
        }

        return UiModuleDeps(
            moduleName = moduleName,
            buildSystem = buildSystem,
            acceptsAar = accepts,
            declared = declaredRoots,
            nodes = nodes.values.toList(),
            conflicts = result?.conflicts?.map { UiVersionConflict(it.coordinate, it.requested, it.chosen) }.orEmpty(),
            cycles = detectCycles(edges),
            unresolved = unresolved.toList(),
        )
    }

    /** Repository search (Maven Central), each hit pre-judged against [moduleName]'s AAR compatibility. */
    suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> {
        val module = modules().firstOrNull { it.name == moduleName }
        val accepts = module?.let { acceptsAar(it) } ?: true
        return depsResolver.search(query).map { hit ->
            val isAar = hit.packaging.equals("aar", ignoreCase = true)
            UiArtifactHit(
                coordinate = hit.coordinate.toString(),
                packaging = hit.packaging,
                compatible = !isAar || accepts,
                incompatibleReason = if (isAar && !accepts && module != null) aarReason(module) else null,
            )
        }
    }

    /**
     * Resolve [coordinate] and attach its full closure to [moduleName] as one [LibraryDependency] whose
     * library bundles every resolved jar/aar-classes root. Blocked when incompatible (e.g. an `.aar` on a
     * Java module). Re-indexes so completion/analysis pick up the new classpath.
     */
    suspend fun addDependency(moduleName: String, coordinate: String, scope: String): UiAddResult {
        val module = modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(false, "No module '$moduleName'.")
        val coord = parseCoordinate(coordinate) ?: return UiAddResult(false, "Invalid coordinate — expected group:name:version.")
        if (module.dependencies.any { it is LibraryDependency && it.library.name == coordinate })
            return UiAddResult(false, "$coordinate is already a dependency of '$moduleName'.")

        _depsState.value = DepsResolveState(resolving = true, message = "Resolving $coordinate…")
        val result = try {
            depsResolver.resolve(listOf(coord), DEFAULT_REPOSITORIES, ConflictPolicy.NEWEST, depsProgress())
        } catch (e: Exception) {
            return UiAddResult(false, "Resolution failed: ${e.message}")
        } finally {
            _depsState.update { it.copy(resolving = false) }
        }

        val primary = result.resolved.firstOrNull { it.coordinate.group == coord.group && it.coordinate.name == coord.name }
            ?: return UiAddResult(false, "Couldn't find $coordinate in the configured repositories.")
        if (primary.kind == ArtifactKind.AAR && !acceptsAar(module))
            return UiAddResult(false, "$coordinate is an Android library (.aar) — ${aarReason(module)}.")

        store.workspace.libraryTable.create(coordinate).apply {
            kind = if (primary.kind == ArtifactKind.AAR) LibraryKind.AAR else LibraryKind.JAR
            result.resolved.forEach { addClassesRoot(it.classesRoot) }   // declared + its whole transitive closure
            primary.sourcesRoot?.let { addSourcesRoot(it) }
            commit()
        }
        val project = projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")
        project.beginModification().apply {
            module(module.id).addDependency(LibraryDependency(LibraryRef(coordinate), parseScope(scope)))
            commit()
        }
        store.save()
        invalidateAnalyzers()   // dependents' classpaths include this module's exported (api) libraries
        resyncIndex()
        val transitiveCount = result.resolved.size - 1
        val suffix = if (transitiveCount > 0) " (+$transitiveCount transitive)" else ""
        return UiAddResult(true, "Added $coordinate$suffix", result.resolved.size)
    }

    /** Remove the declared library dependency [coordinate] from [moduleName] (returns false if absent). */
    fun removeDependency(moduleName: String, coordinate: String): Boolean {
        val module = modules().firstOrNull { it.name == moduleName } ?: return false
        val entry = module.dependencies.firstOrNull { it is LibraryDependency && it.library.name == coordinate } ?: return false
        val project = projectOf(module) ?: return false
        project.beginModification().apply {
            module(module.id).removeDependency(entry)
            commit()
        }
        store.save()
        invalidateAnalyzers()
        resyncIndex()
        return true
    }

    // ---- module configuration (the Module Settings editor) ----

    /** Modules whose configuration can be edited (the settings screen's switcher). */
    fun configurableModules(): List<UiModuleRef> = modules().map { UiModuleRef(it.name, it.type.displayName) }

    /**
     * Read [moduleName]'s editable configuration: type, language level, source sets, and one facet panel
     * per registered facet. Facet fields are derived generically from the codec's value map, so any
     * codec-backed facet (Android, future ones) renders without bespoke UI.
     */
    fun getModuleConfig(moduleName: String): UiModuleConfig? {
        val module = modules().firstOrNull { it.name == moduleName } ?: return null
        val facets = module.facets.all.mapNotNull { facet ->
            val data = store.facetCodecs.encode(facet) ?: return@mapNotNull null
            UiFacetConfig(data.tomlTable, titleCase(data.tomlTable), data.values.map { (k, v) -> configFieldFor(k, v) })
        }
        return UiModuleConfig(
            name = module.name,
            typeId = module.type.id,
            typeDisplay = module.type.displayName,
            languageLevel = module.languageLevel.name,
            languageLevels = LanguageLevel.values().map { it.name },
            outputDir = module.outputDir.path,
            sourceSets = module.sourceSets.map { ss ->
                UiSourceSetInfo(ss.name, ss.scope.name, ss.contentRoots.map { it.dir.path })
            },
            facets = facets,
        )
    }

    /** Persist [edit] (language level + facet values) to [moduleName] through a model transaction + save. */
    fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult {
        val module = modules().firstOrNull { it.name == moduleName } ?: return UiConfigResult(false, "No module '$moduleName'.")
        val project = projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        val newLevel = edit.languageLevel?.let { runCatching { LanguageLevel.valueOf(it) }.getOrNull() }
        if (edit.languageLevel != null && newLevel == null) return UiConfigResult(false, "Unknown language level '${edit.languageLevel}'.")
        val facets = ArrayList<dev.ide.model.Facet>()
        for ((table, values) in edit.facetValues) {
            val facet = store.facetCodecs.decode(dev.ide.model.impl.FacetData(table, values))
                ?: return UiConfigResult(false, "No codec registered for facet '$table'.")
            facets += facet
        }
        try {
            project.beginModification().apply {
                val mod = module(module.id)
                if (newLevel != null) mod.languageLevel = newLevel
                facets.forEach { mod.putFacet(it) }
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Update failed: ${e.message}")
        }
        store.save()
        invalidateAnalyzers()       // language level + facets affect the compile classpath/source sets
        invalidateSyntheticClasses() // an Android facet change can move the R package
        resyncIndex()
        return UiConfigResult(true, "Saved ${module.name}")
    }

    /** Map a codec value to a typed UI field: Long→Number, Boolean→Bool, String→Text, lists→StringList/TableList. */
    private fun configFieldFor(key: String, value: Any?): UiConfigField = when (value) {
        is Boolean -> UiConfigField.Bool(key, humanizeKey(key), value)
        is Long -> UiConfigField.Number(key, humanizeKey(key), value)
        is Int -> UiConfigField.Number(key, humanizeKey(key), value.toLong())
        is Number -> UiConfigField.Number(key, humanizeKey(key), value.toLong())
        is String -> UiConfigField.Text(key, humanizeKey(key), value)
        is List<*> ->
            if (value.isNotEmpty() && value.all { it is Map<*, *> }) {
                @Suppress("UNCHECKED_CAST")
                val rows = value.map { row -> (row as Map<String, Any?>).map { (k, v) -> configFieldFor(k, v) } }
                UiConfigField.TableList(key, humanizeKey(key), rows)
            } else {
                UiConfigField.StringList(key, humanizeKey(key), value.mapNotNull { it as? String })
            }
        else -> UiConfigField.Text(key, humanizeKey(key), value?.toString() ?: "")
    }

    private fun titleCase(s: String): String = s.replaceFirstChar { it.uppercase() }

    /** "compileSdk" → "Compile Sdk", "applicationIdSuffix" → "Application Id Suffix". */
    private fun humanizeKey(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    /** Tarjan-lite cycle detection over the resolved `dependsOn` [edges] — surfaces metadata cycles to the UI. */
    private fun detectCycles(edges: Map<String, List<String>>): List<List<String>> {
        val cycles = ArrayList<List<String>>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        val stack = ArrayList<String>()
        fun dfs(node: String) {
            if (node in onStack) {
                val idx = stack.indexOf(node)
                if (idx >= 0) cycles += stack.subList(idx, stack.size).toList() + node
                return
            }
            if (node in done) return
            onStack += node; stack += node
            edges[node].orEmpty().forEach(::dfs)
            stack.removeAt(stack.lastIndex); onStack -= node; done += node
        }
        edges.keys.toList().forEach(::dfs)
        return cycles.distinctBy { it.toSet() }
    }

    /** First module (by source scan) whose source declares a `static void main(String[])`, + its FQN. */
    private fun findRunnable(): Pair<Module, String>? {
        for (module in modules()) {
            for (root in sourceRoots(module)) {
                if (!Files.isDirectory(root)) continue
                val files = runCatching { Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.toList() } }
                    .getOrDefault(emptyList())
                files.firstNotNullOfOrNull { mainClassIn(it) }?.let { return module to it }
            }
        }
        return null
    }

    private fun mainClassIn(file: Path): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (!MAIN_METHOD.containsMatchIn(text)) return null
        val pkg = PACKAGE_DECL.find(text)?.groupValues?.get(1)
        val cls = file.fileName.toString().removeSuffix(".java")
        return if (pkg.isNullOrEmpty()) cls else "$pkg.$cls"
    }

    // Live editor buffers (absolute path -> text). Surfaced to the JDT analyzer as an in-memory overlay
    // (FQCN -> source) so completion resolves in-progress edits to dependency files without ever
    // touching disk — true working copies, not flushed buffers.
    private val openDocuments = ConcurrentHashMap<Path, String>()

    /** Called by the editor on every change so cross-file analysis sees the latest text. */
    fun updateDocument(file: Path, text: String) {
        openDocuments[file.toAbsolutePath().normalize()] = text
    }

    /** Persist a single editor buffer to disk and keep it as the live overlay (it now equals disk). */
    fun save(file: Path, text: String) {
        val path = file.toAbsolutePath().normalize()
        openDocuments[path] = text
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(text)
        if (isResourcePath(path)) {
            invalidateSyntheticClasses() // an edited res file changes R
            if (path.toString().endsWith(".xml")) {
                indexScope.launch { runCatching { indexService.reindexSource(path, text) } } // keep the resource index current
            }
        } else if (isKotlin(path)) {
            invalidateSyntheticClasses() // an edited .kt file changes its `<File>Kt` facade / class shapes
        }
    }

    /** A path under an Android `res/` tree — a change to it can change the synthetic R class. */
    private fun isResourcePath(path: Path): Boolean = path.toString().replace('\\', '/').contains("/res/")

    /**
     * Write live editor buffers to disk ("save all"). Completion runs off the in-memory overlay, but the
     * compiler reads sources from disk, so a build must flush first, or it compiles stale files. Only
     * writes when content actually changed.
     */
    private fun flushOpenDocuments() {
        for ((path, content) in openDocuments) {
            runCatching {
                if (!Files.exists(path) || path.readText() != content) {
                    path.parent?.let { Files.createDirectories(it) }
                    path.writeText(content)
                }
            }
        }
    }

    // Synthetic ("light") classes (e.g. Android R) contributed via `platform.syntheticClass`, rendered to
    // Java source and merged into the overlay so they resolve for completion AND analysis like real types.
    // Cached (the scan walks resource dirs); invalidated when resources change (save/createFile/reindex).
    @Volatile private var syntheticCache: Map<String, CharArray>? = null

    private fun syntheticOverlay(): Map<String, CharArray> {
        syntheticCache?.let { return it }
        val providers = platform.extensions.extensions(SYNTHETIC_CLASS_EP)
        if (providers.isEmpty()) return emptyMap<String, CharArray>().also { syntheticCache = it }
        val out = HashMap<String, CharArray>()
        for (m in modules()) {
            val ctx = object : SyntheticClassContext {
                override val module = m
                override val workspace = store.workspace
            }
            for (provider in providers) {
                val classes = runCatching { provider.classesFor(ctx) }.getOrDefault(emptyList())
                for (sc in classes) runCatching { out[sc.fqName] = SyntheticJavaSource.emit(sc).toCharArray() }
            }
        }
        return out.also { syntheticCache = it }
    }

    // Structured synthetic classes a KOTLIN file in a module should see (the Kotlin backend resolves them
    // directly, not via rendered Java source). Cached per module, swapped out (fresh list) on invalidation so
    // the symbol service — which caches by list identity — picks up resource changes.
    private val kotlinSyntheticCache = ConcurrentHashMap<String, List<SyntheticClass>>()

    /**
     * The synthetic ("light") classes a Kotlin file in [module] should resolve — Android `R`/`BuildConfig`,
     * ViewBinding, etc. — but NOT the Kotlin `<File>Kt` facades: those are Java-visible shapes of this
     * module's own Kotlin source, and a Kotlin file references its top-level declarations directly (seeing the
     * facade would be wrong and duplicate). Excludes the [KotlinSyntheticClassProvider] for exactly that reason.
     */
    private fun kotlinSyntheticClasses(module: Module): List<SyntheticClass> =
        kotlinSyntheticCache.getOrPut(module.id.value) {
            val ctx = object : SyntheticClassContext {
                override val module = module
                override val workspace = store.workspace
            }
            platform.extensions.extensions(SYNTHETIC_CLASS_EP)
                .filterNot { it is dev.ide.lang.kotlin.synthetic.KotlinSyntheticClassProvider }
                .flatMap { runCatching { it.classesFor(ctx) }.getOrDefault(emptyList()) }
        }

    /** Drop the cached synthetic classes so the next analyze/complete regenerates them (e.g. after a res edit). */
    fun invalidateSyntheticClasses() { syntheticCache = null; kotlinSyntheticCache.clear() }

    /** The open buffers as an FQCN -> source overlay for the name environment, plus synthetic light classes. */
    private fun overlay(): Map<String, CharArray> {
        val synthetic = syntheticOverlay()
        val map = HashMap<String, CharArray>(synthetic.size + openDocuments.size)
        map.putAll(synthetic)
        for ((path, content) in openDocuments) {
            val fqcn = fqcnOf(path, content) ?: continue
            map[fqcn] = content.toCharArray() // a real, open file overrides a synthetic of the same FQCN
        }
        return map
    }

    private fun fqcnOf(path: Path, text: String): String? {
        val cls = path.fileName.toString().removeSuffix(".java").takeIf { it.isNotEmpty() } ?: return null
        val pkg = PACKAGE_DECL.find(text)?.groupValues?.get(1)
        return if (pkg.isNullOrEmpty()) cls else "$pkg.$cls"
    }

    val workspaceRoot: Path get() = store.rootPath

    fun modules(): List<Module> = store.workspace.projects.flatMap { it.modules }

    /** Module display names — so a host can log/show them without depending on the model API. */
    fun moduleNames(): List<String> = modules().map { it.name }

    /** The project templates registered into this workspace's platform (the Create-Project gallery). */
    fun projectTemplates(): List<ProjectTemplate> = ProjectTemplateRegistry(platform.extensions).all()

    /** The model project's display name (e.g. "MyApp"), falling back to the workspace dir name. */
    fun projectDisplayName(): String =
        store.workspace.projects.firstOrNull()?.name ?: (workspaceRoot.fileName?.toString() ?: "workspace")

    fun sourceRoots(module: Module): List<Path> =
        module.sourceSets
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { Paths.get(it.dir.path) }

    /** Content roots to surface in the project tree — code, resources, and assets (so `res/` is editable). */
    fun treeRoots(module: Module): List<Path> {
        return module.sourceSets
            .flatMap { it.contentRoots }
            .filter { cr -> cr.roles.any { it in TREE_ROOT_ROLES } }
            .map { Paths.get(it.dir.path) }
    }

    /**
     * The same surfaced roots as [treeRoots], but keeping each root's source-set name and [ContentRole]s
     * so the tree can pick a distinct icon (Java sources vs `res/` vs `assets/`) and know which roots are
     * package contexts (compactable + new-class targets).
     */
    fun treeRootsDetailed(module: Module): List<TreeRootInfo> =
        module.sourceSets.flatMap { ss ->
            ss.contentRoots
                .filter { cr -> cr.roles.any { it in TREE_ROOT_ROLES } }
                .map { TreeRootInfo(Paths.get(it.dir.path), ss.name, it.roles) }
        }

    /**
     * The module's `AndroidManifest.xml` (from its [AndroidFacet]), or null for a non-Android module. It
     * sits above the source roots, so the tree surfaces it explicitly. Resolved relative to the module dir
     * (derived from the output dir's `build/classes` convention).
     */
    fun manifestPath(module: Module): Path? {
        val facet = module.facets.get(dev.ide.android.support.AndroidFacet.KEY) ?: return null
        val moduleDir = Paths.get(module.outputDir.path).parent?.parent ?: return null
        return moduleDir.resolve(facet.manifest)
    }

    /** The module that owns [file] (its source root is a prefix), or null if outside the project. */
    fun moduleForFile(file: Path): Module? {
        val target = file.toAbsolutePath().normalize()
        return modules().firstOrNull { module -> sourceRoots(module).any { target.startsWith(it) } }
    }

    private fun analyzerFor(module: Module, language: LanguageId = LanguageId("java")): SourceAnalyzer =
        analyzers.getOrPut("${module.id.value}:${language.id}") {
            backendFor(language).createAnalyzer(ModuleCompilationContext.create(store.workspace, module)).also {
                when (it) {
                    is JdtSourceAnalyzer -> {
                        it.overlayProvider = ::overlay
                        it.indexService = indexService
                        // A downloaded JDK's src.zip (if any) → JDK names/javadoc for the editor.
                        sdkManager.jdkSourceOverride()?.let { zip -> it.addSourceJars(listOf(zip)) }
                    }
                    is KotlinSourceAnalyzer -> {
                        // Java/Android interop via the shared `java.classNames` index (type-name completion);
                        // the classpath extension scan persists alongside the index caches.
                        it.indexService = indexService
                        it.extensionCacheDir = store.rootPath.resolve(".platform/caches/kotlin-ext")
                        // Synthetic "light" classes (Android R/BuildConfig, …), minus the Kotlin file facades.
                        it.syntheticClassProvider = { kotlinSyntheticClasses(module) }
                    }
                    is XmlSourceAnalyzer -> {
                        // Inject the Android knowledge: layout metadata (SDK attrs.xml asset when present, else
                        // the curated catalog) + custom-view attributes (project/AAR attrs.xml, parsed once per
                        // analyzer) + resource references (the module's merged repository, rebuilt per request).
                        val custom = runCatching { customAttrsMetadata(module) }.getOrNull()
                        it.contributors = listOf(
                            AndroidXmlContributor(
                                resourceNames = { type -> resourceNamesFor(module, type) },
                                layout = { sdkLayoutMetadata() },
                                customAttrs = { custom },
                            )
                        )
                    }
                }
                // NB: disposal is driven by invalidateAnalyzers()/close() below, NOT platform.register — an
                // evicted analyzer must be disposed promptly so its cached library-jar handles (the Kotlin
                // ClasspathReader's ZipFiles, the JDT env cache) are released. Registering them with the
                // platform only disposed at workspace close, so every eviction leaked open jars until GC —
                // surfacing on ART as a flood of "A ZipFile failed to close" CloseGuard warnings.
            }
        }

    /**
     * Drop every cached per-module analyzer. A dependency change on one module affects the compile
     * classpath of every module that depends on it (an exported (`api`) library flows downstream), so a
     * per-module eviction would leave dependents analyzing against a stale classpath. Analyzers rebuild
     * lazily on the next analyze/complete. Each evicted analyzer is disposed so its open library-jar
     * handles are closed immediately (dispose is idempotent — a final close() pass is harmless).
     */
    private fun invalidateAnalyzers() {
        val evicted = analyzers.values.toList()
        analyzers.clear()
        evicted.forEach { (it as? Disposable)?.let { d -> runCatching { d.dispose() } } }
    }

    /**
     * The module that owns [file] for editing purposes — a source/generated root (Java), an Android `res/`
     * tree (XML resources), or the module's `AndroidManifest.xml`. Broader than [moduleForFile], which only
     * matches source roots, so XML files under `res/` and the manifest resolve to their module.
     */
    private fun moduleForEditableFile(file: Path): Module? =
        moduleForFile(file) ?: moduleForResourceFile(file) ?: moduleForManifestFile(file)

    /** The module whose [AndroidFacet] manifest path is [file], or null. */
    private fun moduleForManifestFile(file: Path): Module? {
        val target = file.toAbsolutePath().normalize()
        return modules().firstOrNull { manifestPath(it)?.toAbsolutePath()?.normalize() == target }
    }

    /** Completion for [text] (the live editor buffer) at [offset], bound to [file]'s module + language. */
    fun complete(file: Path, text: String, offset: Int): CompletionResult {
        val module = moduleForEditableFile(file)
            ?: return CompletionResult(emptyList(), false, TextRange(offset, offset))
        updateDocument(file, text) // the live buffer becomes part of the overlay the analyzer reads
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.completion
            ?: return CompletionResult(emptyList(), false, TextRange(offset, offset))
        val snapshot = EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text)
        // A backend that throws mid-completion (e.g. the Kotlin parse host on ART) would otherwise propagate
        // out to the UI and surface as a silent empty popup with no trace. Log the cause (logcat/stderr) and
        // degrade to no suggestions, so one failing file can't disable completion and the failure is diagnosable.
        return runCatching {
            runSync { service.complete(CompletionRequest(snapshot, offset, CompletionTrigger.Explicit)) }
        }.getOrElse { e ->
            System.err.println("[IdeServices] completion failed for $file (${languageFor(file).id}): ${e.stackTraceToString()}")
            CompletionResult(emptyList(), false, TextRange(offset, offset))
        }
    }

    /** Inlay hints for [text] (the live buffer) in `[startOffset, endOffset)`, bound to [file]'s module +
     *  language. Empty when the file is outside the project or its backend has no inlay-hint service. */
    fun inlayHints(file: Path, text: String, startOffset: Int, endOffset: Int): List<dev.ide.lang.hints.InlayHint> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.inlayHints ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        return runCatching { runSync { service.hints(vf, TextRange(startOffset, endOffset)) } }.getOrDefault(emptyList())
    }

    /** Enclosing declarations (type/method names, outer→inner) at [offset] in [file]'s buffer — for the
     *  cursor-tracking breadcrumb. Empty if the file is outside the project or not JDT-backed. */
    fun breadcrumbAt(file: Path, text: String, offset: Int): List<String> {
        val module = moduleForFile(file) ?: return emptyList()
        updateDocument(file, text)
        val analyzer = analyzerFor(module) as? JdtSourceAnalyzer ?: return emptyList()
        return runCatching { analyzer.enclosingStructure(store.vfs.fileFor(file), text, offset) }.getOrDefault(emptyList())
    }

    /** Diagnostics for [text], bound to [file]'s module (empty if outside the project). */
    fun analyze(file: Path, text: String): AnalysisResult? {
        val module = moduleForFile(file) ?: return null
        val analyzer = analyzerFor(module, languageFor(file))
        val vf = store.vfs.fileFor(file)
        // Reuse the analyzer's incremental parser on the live buffer.
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return runSync { analyzer.analyze(vf) }
    }

    // ---- block-based editing (projectional editor) ----

    /** Java statements + key expressions decompose; the rest collapse to editable text slots. */
    // Block mappings come through the `platform.blockMapping` EP, so a plugin (e.g. a Kotlin backend) can
    // contribute its own decomposition. The built-in Java mapping is registered here as the platform's own.
    private val blockService = run {
        platform.extensions.register(BLOCK_MAPPING_EP, JavaBlockMapping, PluginId("java-support"))
        BlockProjectionEngine(platform.extensions.extensions(BLOCK_MAPPING_EP))
    }

    /**
     * Project [file]'s live buffer [text] into a [BlockTree] — the same tolerant DOM the analyzer/completion
     * use, parsed via the module's incremental parser. Null if [file] is outside the project. Projection is
     * deterministic for identical text, so the ids it assigns are stable enough to round-trip a block edit
     * (which re-projects the same text to resolve them) without holding any session state.
     */
    fun projectBlocks(file: Path, text: String): BlockTree? {
        val module = moduleForFile(file) ?: return null
        val analyzer = analyzerFor(module, languageFor(file))
        val parsed = analyzer.incrementalParser.parseFull(EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text))
        return blockService.project(parsed)
    }

    /** Compile a [BlockEdit] against [file]'s buffer [text] into surgical document edits (empty if N/A). */
    fun computeBlockEdit(file: Path, text: String, edit: BlockEdit): List<DocumentEdit> {
        val tree = projectBlocks(file, text) ?: return emptyList()
        return blockService.computeEdit(tree, text, edit)
    }

    // ---- analysis (diagnostics) ----

    private val analysisEnvironment = IdeAnalysisEnvironment()

    /**
     * The diagnostics engine: the JDT compiler (as a provider) + built-in Java analyzers, merged into one
     * set. The built-ins are registered into the `platform.analyzer` / `platform.diagnosticProvider`
     * extension points and the engine is assembled by *querying* the registry — so a plugin can contribute
     * analyzers, diagnostic providers, or quick-fixes and have them merge into the same pipeline.
     */
    private val analysisEngine = run {
        val plugin = PluginId("java-analysis")
        platform.extensions.register(ANALYZER_EP, SystemOutCallAnalyzer(), plugin)
        platform.extensions.register(ANALYZER_EP, UnusedImportAnalyzer(), plugin)
        platform.extensions.register(DIAGNOSTIC_PROVIDER_EP, CompilerDiagnosticProvider(), plugin)
        // Code actions: quick-fixes attach to diagnostics by code; action providers offer caret intentions.
        platform.extensions.register(QUICK_FIX_PROVIDER_EP, AddImportQuickFixProvider(), plugin)
        platform.extensions.register(QUICK_FIX_PROVIDER_EP, RemoveUnusedImportQuickFixProvider(), plugin)
        platform.extensions.register(ACTION_PROVIDER_EP, IntroduceVariableActionProvider(), plugin)
        platform.extensions.register(ACTION_PROVIDER_EP, SurroundWithTryCatchActionProvider(), plugin)
        AnalysisEngine(
            analyzers = platform.extensions.extensions(ANALYZER_EP),
            quickFixProviders = platform.extensions.extensions(QUICK_FIX_PROVIDER_EP),
            diagnosticProviders = platform.extensions.extensions(DIAGNOSTIC_PROVIDER_EP),
            environment = analysisEnvironment,
            scope = indexScope,
            actionProviders = platform.extensions.extensions(ACTION_PROVIDER_EP),
        )
    }

    // The analysis pipeline parses via JDT's DOM ASTParser, which on ART can reference JDK-9+ platform
    // classes absent from the runtime (e.g. java.lang.Runtime$Version, surfaced as a LinkageError, not a
    // catchable Exception). If that happens once, analysis is disabled rather than throwing on every edit.
    // On a JVM (desktop) this never trips. Completion is unaffected; it uses the low-level compiler path.
    @Volatile private var analysisUnavailable = false

    /** Run the full pipeline (JDT compiler + Java analyzers, suppression + profile) over [file]'s live buffer. */
    fun analyzeDiagnostics(file: Path, text: String): List<dev.ide.analysis.Diagnostic> {
        val name = file.fileName?.toString()
        if (name?.endsWith(".xml") == true) return analyzeXml(file, text)
        // Kotlin has its own tolerant PSI parse + diagnostics; it must NOT go through the JDT engine
        // (which would parse `.kt` as Java and report spurious "Syntax error / insert ';'" diagnostics).
        if (name?.endsWith(".kt") == true || name?.endsWith(".kts") == true) return analyzeKotlin(file, text)
        if (analysisUnavailable || moduleForFile(file) == null) return emptyList()
        updateDocument(file, text)
        return try {
            runSync { analysisEngine.analyzeNow(store.vfs.fileFor(file)) }
        } catch (e: LinkageError) {
            analysisUnavailable = true
            emptyList()
        }
    }

    /** Kotlin diagnostics: the tolerant PSI parser's syntax errors + semantic checks (unresolved
     *  references). No Java/JDT involvement. */
    private fun analyzeKotlin(file: Path, text: String): List<dev.ide.analysis.Diagnostic> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID)
        val vf = store.vfs.fileFor(file)
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return runSync { analyzer.analyze(vf) }.diagnostics.map { d ->
            // The tolerant parser tags syntax errors `kt.syntax`; everything else is a semantic finding.
            val id = if (d.code == "kt.syntax") "kotlin.syntax" else "kotlin.semantic"
            dev.ide.analysis.Diagnostic(d.range, d.severity, d.message, DiagnosticSource.Analyzer(AnalyzerId(id)), d.code)
        }
    }

    /**
     * Code actions (quick-fixes + caret intentions) available for [file]'s live buffer [text] over the
     * selection `[start, end)` — what the editor lightbulb / Alt-Enter menu lists. Stable order, so the host
     * round-trips a chosen action back through [applyEditorAction] by its list [Int] index.
     */
    fun editorActions(file: Path, text: String, start: Int, end: Int): List<dev.ide.analysis.QuickFix> {
        if (file.fileName?.toString()?.endsWith(".xml") == true)
            return xmlActionsAt(file, text, start).map { StaticQuickFix(it.title) } // XML quick-fixes
        if (isKotlin(file)) return emptyList() // no Kotlin quick-fixes; keep the JDT engine off `.kt`
        if (analysisUnavailable || moduleForFile(file) == null) return emptyList()
        updateDocument(file, text)
        return try {
            runSync { analysisEngine.editorActionsAt(store.vfs.fileFor(file), TextRange(start, end)) }
        } catch (e: LinkageError) {
            analysisUnavailable = true
            emptyList()
        }
    }

    /**
     * Compute (don't apply) the edits of the action at [index] in [editorActions]'s list for [file]'s buffer
     * [text] at `[start, end)`, returning the document edits for [file] itself — the editor applies them to
     * its buffer (the action round-trip, like a block edit). Edits in other files (rare; none of the
     * built-ins do this) are not returned here.
     */
    fun applyEditorAction(file: Path, text: String, start: Int, end: Int, index: Int): List<DocumentEdit> {
        if (file.fileName?.toString()?.endsWith(".xml") == true)
            return runCatching { xmlActionsAt(file, text, start).getOrNull(index)?.apply() }.getOrNull() ?: emptyList()
        if (isKotlin(file)) return emptyList()
        if (analysisUnavailable || moduleForFile(file) == null) return emptyList()
        updateDocument(file, text)
        val vf = store.vfs.fileFor(file)
        return try {
            runSync { analysisEngine.computeActionEdits(vf, TextRange(start, end), index) }
                .edits.entries.firstOrNull { it.key.path == vf.path }?.value ?: emptyList()
        } catch (e: LinkageError) {
            analysisUnavailable = true
            emptyList()
        }
    }

    // ---- XML layout completion metadata ----

    // The SDK-derived metadata (widget/attribute set from attrs.xml + the android.jar hierarchy).
    // A workspace override (`.platform/android-sdk-metadata.txt`, e.g. a different API level generated by the
    // `:android-sdk-metadata` tool) wins; otherwise the asset bundled in android-support is used. Never null,
    // so completion covers the full set out of the box (including parent layout params such as
    // RelativeLayout's layout_below/toEndOf).
    @Volatile private var sdkMetadata: AndroidSdkMetadata? = null

    private fun sdkLayoutMetadata(): AndroidSdkMetadata {
        sdkMetadata?.let { return it }
        val path = store.rootPath.resolve(".platform/android-sdk-metadata.txt")
        val md = runCatching {
            if (Files.exists(path)) SdkMetadataCodec.read(path.readText()) else null
        }.getOrNull() ?: AndroidSdkMetadata.bundled()
        return md.also { sdkMetadata = it }
    }

    /**
     * Custom-view attributes for [module]: parses every `attrs.xml`-style file in the module's resource roots
     * (project + AAR), merging their `<declare-styleable>`/`<attr>` definitions into an `app:`-prefixed
     * [AndroidSdkMetadata] (no class hierarchy — a custom view's own attrs; framework View attrs are added by
     * the layout metadata). Null when the module declares no custom attributes.
     */
    private fun customAttrsMetadata(module: Module): AndroidSdkMetadata? {
        val dirs = runCatching { AndroidResources.resourceDirs(module, store.workspace) }.getOrDefault(emptyList())
        val attrs = LinkedHashMap<String, AttrEntry>()
        val styleables = LinkedHashMap<String, StyleableEntry>()
        for (dir in dirs) {
            val valuesDirs = runCatching {
                Files.list(dir).use { it.toList() }
            }.getOrDefault(emptyList())
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values") }
            for (vd in valuesDirs) {
                val files = runCatching { Files.list(vd).use { it.toList() } }.getOrDefault(emptyList())
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                for (f in files) {
                    val text = runCatching { f.readText() }.getOrNull() ?: continue
                    if (!text.contains("declare-styleable") && !text.contains("<attr")) continue
                    val parsed = AttrsXmlParser.parse(text)
                    attrs.putAll(parsed.attrs)
                    styleables.putAll(parsed.styleables)
                }
            }
        }
        if (attrs.isEmpty() && styleables.isEmpty()) return null
        return AndroidSdkMetadata(0, attrs, styleables, emptyMap(), emptyList(), attrPrefix = "app:")
    }

    /**
     * Diagnostics for an XML file: XML well-formedness (from the XML language backend's tolerant parser:
     * unclosed/mismatched tags, unterminated values) merged with the Android resource-reference checks.
     * Both flow into the one [dev.ide.analysis.Diagnostic] stream the editor renders, as for Java.
     */
    private fun analyzeXml(file: Path, text: String): List<dev.ide.analysis.Diagnostic> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(module, XmlLanguageBackend.LANGUAGE_ID)
        val vf = store.vfs.fileFor(file)
        val parsed = analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        val out = ArrayList<dev.ide.analysis.Diagnostic>()
        // Well-formedness (the tolerant parser's own diagnostics).
        parsed.diagnostics.forEach { d ->
            out += dev.ide.analysis.Diagnostic(d.range, d.severity, d.message, DiagnosticSource.Analyzer(AnalyzerId("xml.syntax")), d.code)
        }
        // Android/lint inspections.
        for (f in xmlFindings(module, file, text, parsed)) {
            out += dev.ide.analysis.Diagnostic(f.range, f.severity, f.message, DiagnosticSource.Analyzer(AnalyzerId("android.xml")), f.code)
        }
        return out
    }

    // ---- XML inspections + quick-fixes ----

    /** A fix: a user-facing [title] and an [apply] that performs any file side effects (e.g. writing a new
     *  `<string>` into strings.xml) and returns the edits to splice into the *current* buffer. */
    private data class XmlFix(val title: String, val apply: () -> List<DocumentEdit>)
    private data class XmlFinding(
        val range: TextRange, val severity: Severity, val message: String, val code: String,
        val fixes: List<XmlFix> = emptyList(),
    )

    /** Run the Android XML inspections over [parsed]. Detection is pure ([XmlLintRules]); a fix's I/O happens
     *  only on apply. */
    private fun xmlFindings(module: Module, file: Path, text: String, parsed: ParsedFile): List<XmlFinding> {
        val out = ArrayList<XmlFinding>()
        val isLayout = file.toString().replace('\\', '/').contains("/res/layout")

        // A) Missing xmlns:android when android: attributes are used.
        XmlLintRules.missingNamespace(parsed)?.let { hit ->
            out += XmlFinding(
                hit.range, Severity.ERROR, "Missing xmlns:android namespace declaration", "android.missingNamespace",
                listOf(XmlFix("Add xmlns:android declaration") { listOf(DocumentEdit(hit.insertAt, 0, " xmlns:android=\"${hit.uri}\"")) }),
            )
        }

        if (isLayout) {
            // B) Hardcoded user-facing text → extract to @string.
            for (h in XmlLintRules.hardcodedText(parsed)) {
                val resName = snakeName(h.value)
                out += XmlFinding(
                    h.range, Severity.WARNING, "Hardcoded string should be a @string resource", "android.hardcodedText",
                    listOf(XmlFix("Extract to @string resource") {
                        val n = appendValueResource(module, ResourceType.STRING, resName, h.value)
                        listOf(DocumentEdit(h.range.start, h.range.length, "@string/$n"))
                    }),
                )
            }
            // C) A view element missing layout_width / layout_height.
            for (m in XmlLintRules.missingSize(parsed, ::isViewLike)) {
                out += XmlFinding(
                    m.range, Severity.WARNING, "<${m.tag}> is missing android:${m.dim}", "android.missingSize",
                    listOf(XmlFix("Add android:${m.dim}=\"wrap_content\"") {
                        listOf(DocumentEdit(m.insertAt, 0, "\n    android:${m.dim}=\"wrap_content\""))
                    }),
                )
            }
        }

        // D) Unresolved local resource references. Resolved via the resource index, falling back to the
        // (synchronous) repository while the index is still building, so a fresh workspace still validates.
        val refs = ResourceReferences.scan(text).filter {
            it.isLocal && !it.create && !it.themeAttr && it.type != null && it.name !in setOf("null", "empty", "undefined")
        }
        if (refs.isNotEmpty()) {
            val repo by lazy { runCatching { AndroidResources.repository(module, store.workspace) }.getOrNull() }
            for (ref in refs) {
                val type = ref.type!!
                val resName = sanitizeResName(ref.name)
                val typeHasAny = indexTypeHasAny(type) || (repo?.names(type)?.isNotEmpty() == true)
                if (!typeHasAny) continue                    // type only sourced from framework/unindexed → don't flag
                if (indexHasResource(type, resName) || repo?.has(type, resName) == true) continue
                val range = TextRange(ref.range.first, ref.range.last + 1)
                val fixes = if (type.isValueType()) listOf(XmlFix("Create @${type.rClass}/$resName") {
                    appendValueResource(module, type, resName, ""); emptyList()
                }) else emptyList()
                out += XmlFinding(range, Severity.WARNING, "Cannot resolve @${type.rClass}/${ref.name}", "android.unresolvedResource", fixes)
            }
        }
        return out
    }

    private fun ResourceType.isValueType(): Boolean = this in setOf(
        ResourceType.STRING, ResourceType.COLOR, ResourceType.DIMEN, ResourceType.BOOL, ResourceType.INTEGER, ResourceType.ID,
    )

    /** A tag that should carry layout params: a known framework widget (from the SDK metadata) or a custom view. */
    private fun isViewLike(tag: String): Boolean =
        sdkLayoutMetadata().isWidgetTag(tag) || tag.contains('.')

    /** A deterministic snake_case resource name from arbitrary [value] (for extract-to-@string). */
    private fun snakeName(value: String): String {
        val base = value.trim().lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .trim('_').replace(Regex("_+"), "_").take(40).ifEmpty { "text" }
        return if (base.first().isDigit()) "_$base" else base
    }

    /** Append `<type name="name">value</type>` to the module's `res/values/<file>.xml` (creating it if needed),
     *  de-duplicating the name, then refresh R + the resource index. Returns the (possibly suffixed) name. */
    private fun appendValueResource(module: Module, type: ResourceType, name: String, value: String): String {
        val valuesDir = resourceRoots(module).firstOrNull()?.resolve("values") ?: return name
        val target = valuesDir.resolve(valuesFileName(type))
        val existing = runCatching { if (Files.exists(target)) target.readText() else null }.getOrNull()
        var unique = name; var i = 1
        while (existing != null && Regex("name\\s*=\\s*\"${Regex.escape(unique)}\"").containsMatchIn(existing)) unique = "${name}_${i++}"
        val entry = "    <${type.rClass} name=\"$unique\">${escapeXml(value)}</${type.rClass}>\n"
        runCatching {
            Files.createDirectories(valuesDir)
            if (existing == null) {
                target.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$entry</resources>\n")
            } else {
                val idx = existing.lastIndexOf("</resources>")
                val merged = if (idx >= 0) existing.substring(0, idx) + entry + existing.substring(idx) else existing + entry
                target.writeText(merged)
            }
            invalidateSyntheticClasses()
            indexScope.launch { runCatching { indexService.reindexSource(target, target.readText()) } }
        }
        return unique
    }

    private fun valuesFileName(type: ResourceType): String = when (type) {
        ResourceType.STRING -> "strings.xml"
        ResourceType.COLOR -> "colors.xml"
        ResourceType.DIMEN -> "dimens.xml"
        else -> "values.xml"
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** XML code actions at the caret: the fixes of every finding whose range contains [offset]. Stable order. */
    private fun xmlActionsAt(file: Path, text: String, offset: Int): List<XmlFix> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(module, XmlLanguageBackend.LANGUAGE_ID)
        val parsed = analyzer.incrementalParser.parseFull(EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text))
        return xmlFindings(module, file, text, parsed).filter { offset in it.range }.flatMap { it.fixes }
    }

    /** A title-only [dev.ide.analysis.QuickFix] for the XML lightbulb. Edits are applied via [applyEditorAction]
     *  (by list index), so [computeEdits] is never invoked through this object. */
    private class StaticQuickFix(override val title: String) : dev.ide.analysis.QuickFix {
        override val kind = dev.ide.analysis.CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: dev.ide.analysis.FixContext): WorkspaceEdit = WorkspaceEdit.EMPTY
    }

    /**
     * Namespace-aware resource-reference checks for an Android res XML: `@string/x`, `@drawable/y`, … are
     * resolved against the module's merged [AndroidResources] repository; unresolved local references
     * are flagged (framework `@android:`, other-package, `?attr/…` theme, and `@+id` refs are never flagged).
     */
    private fun analyzeResourceXml(file: Path, text: String): List<dev.ide.analysis.Diagnostic> {
        val module = moduleForResourceFile(file) ?: return emptyList()
        val repo = AndroidResources.repository(module, store.workspace)
        return ResourceReferences.problems(text, repo).map { p ->
            dev.ide.analysis.Diagnostic(
                range = TextRange(p.start, p.end),
                severity = Severity.WARNING,
                message = p.message,
                source = DiagnosticSource.Analyzer(AnalyzerId("android.resourceReferences")),
                code = "android.unresolvedResource",
            )
        }
    }

    /**
     * Go-to-definition for the Android resource the caret sits on — an `@type/name` reference in res XML, or
     * an `R.type.name` access in Java — resolved against the module's merged resources to its declaration
     * (file + offset). Local references only; framework (`@android:`) / other-namespace refs return null.
     */
    fun definitionAt(file: Path, text: String, offset: Int): Pair<Path, Int>? {
        val isXml = file.fileName?.toString()?.endsWith(".xml") == true
        val module = (if (isXml) moduleForResourceFile(file) else moduleForFile(file)) ?: return null
        if (module.facets.get(dev.ide.android.support.AndroidFacet.KEY) == null) return null
        val (type, name) = (if (isXml) xmlResourceRefAt(text, offset) else rClassRefAt(text, offset)) ?: return null
        // Prefer the resource index — it carries the precise declaration offset; fall back to the repository.
        indexDefinition(type, name)?.let { return it }
        val item = AndroidResources.repository(module, store.workspace).definitions(type, name).firstOrNull() ?: return null
        val src = item.source ?: return null
        return src to declarationOffset(src, name)
    }

    /** The local resource reference under [offset] in res XML (`@type/name`), as (type, R-field name). */
    private fun xmlResourceRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val ref = ResourceReferences.scan(text).firstOrNull { offset in it.range } ?: return null
        val type = ref.type
        if (!ref.isLocal || ref.create || type == null) return null
        return type to sanitizeResName(ref.name)
    }

    /** An `R.type.name` access under [offset] in Java, as (type, name) — tolerant of where in it the caret is. */
    private fun rClassRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val n = text.length
        if (offset < 0 || offset > n) return null
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '$'
        var s = offset.coerceIn(0, n)
        var e = offset.coerceIn(0, n)
        while (s > 0 && part(text[s - 1])) s--
        while (e < n && part(text[e])) e++
        val segs = text.substring(s, e).split('.').filter { it.isNotEmpty() }
        val ri = segs.indexOf("R")
        if (ri < 0 || ri + 2 >= segs.size) return null
        return (ResourceType.byRClass(segs[ri + 1]) ?: return null) to segs[ri + 2]
    }

    /** Offset of the `name="…"` declaration of [sanitizedName] in [file] (a value resource), else the file top. */
    private fun declarationOffset(file: Path, sanitizedName: String): Int {
        val text = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() ?: return 0
        return NAME_ATTR.findAll(text).firstOrNull { sanitizeResName(it.groupValues[1]) == sanitizedName }?.range?.first ?: 0
    }

    /** Resolve a resource to its declaration (file + offset) via the resource index, or null. */
    private fun indexDefinition(type: ResourceType, name: String): Pair<Path, Int>? =
        indexService.exact<ResourceDeclValue>(AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name))
            .firstOrNull()?.let { Paths.get(it.filePath) to it.offset }

    // --- resource preview ------------------------------------------------------------------------------

    /**
     * A render-ready model of the drawable XML in [file] ([text] is the live buffer), with every
     * `@color`/`@dimen`/`@drawable` reference resolved against the module's merged resources. Null for a
     * non-Android module or a file that isn't a drawable/color/mipmap resource.
     */
    fun drawablePreview(file: Path, text: String): DrawablePreview? {
        if (!DrawableXmlCatalog.appliesTo(file.toString())) return null
        val module = moduleForResourceFile(file) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        return runCatching { DrawablePreviewParser.parse(text, drawableResolver(module)) }.getOrNull()
    }

    /** The `<color>` entries of a `res/values` XML [file] ([text] = live buffer), resolved to ARGB for swatches. */
    fun colorResources(file: Path, text: String): List<ColorEntry> {
        val resolver = moduleForResourceFile(file)?.let { drawableResolver(it) } ?: DrawableResolver.NONE
        return runCatching { ColorResources.parse(text, resolver) }.getOrDefault(emptyList())
    }

    /** Raw bytes of a resource file (for bitmap preview); null if unreadable. */
    fun resourceBytes(file: Path): ByteArray? = runCatching { Files.readAllBytes(file) }.getOrNull()

    /** A [DrawableResolver] backed by [module]'s merged resource repository (colors/dimens/nested drawables). */
    private fun drawableResolver(module: Module): DrawableResolver {
        val repo = runCatching { AndroidResources.repository(module, store.workspace) }.getOrNull()
            ?: return DrawableResolver.NONE
        return object : DrawableResolver {
            override fun resolveColor(ref: String): Long? = resolveColorRef(ref, repo, 0)

            override fun resolveDimenDp(ref: String): Float? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                val v = repo.definitions(ResourceType.DIMEN, name).firstOrNull()?.value ?: return null
                return DIMEN_LITERAL.find(v)?.groupValues?.get(1)?.toFloatOrNull()
            }

            override fun resolveDrawable(ref: String): ResolvedDrawable? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                // A @color used where a drawable is expected resolves to a flat fill — let the color path handle it.
                if (ref.contains("color") && repo.has(ResourceType.COLOR, name)) return null
                val item = repo.definitions(ResourceType.DRAWABLE, name).firstOrNull()
                    ?: repo.definitions(ResourceType.MIPMAP, name).firstOrNull()
                    ?: return null
                val src = item.source ?: return null
                val p = src.toString()
                return if (p.endsWith(".xml")) {
                    runCatching { src.readText() }.getOrNull()?.let { ResolvedDrawable.Xml(it) }
                } else {
                    ResolvedDrawable.BitmapFile(item.type.rClass, name, p)
                }
            }
        }
    }

    /** Resolve `@color/x` (transitively through `@color` indirection) to ARGB; `@android:color/x` via the table. */
    private fun resolveColorRef(ref: String, repo: ResourceRepository, depth: Int): Long? {
        if (depth > 8) return null
        val raw = ref.trim()
        if (raw.startsWith("#")) return AndroidColor.parseHex(raw)
        if (raw.contains("android:")) return AndroidColor.framework(raw.substringAfterLast('/'))
        if (!raw.startsWith("@")) return null
        val name = sanitizeResName(raw.substringAfterLast('/'))
        val v = repo.definitions(ResourceType.COLOR, name).firstOrNull()?.value ?: return null
        return when {
            v.startsWith("#") -> AndroidColor.parseHex(v)
            v.startsWith("@") -> resolveColorRef(v, repo, depth + 1)
            else -> null
        }
    }

    private val DIMEN_LITERAL = Regex("""(-?\d+(?:\.\d+)?)""")

    /** Names of every indexed resource of [type], falling back to the repository if the index isn't built
     *  yet. Used by resource-reference completion. */
    private fun resourceNamesFor(module: Module, type: ResourceType): List<String> {
        val out = LinkedHashSet<String>()
        out += indexService.prefix<ResourceDeclValue>(AndroidResourceIndex.id, "${type.rClass}/", limit = 2000)
            .map { it.value.name }
        if (out.isEmpty()) {
            out += runCatching { AndroidResources.repository(module, store.workspace).names(type) }.getOrDefault(emptySet())
        }
        // Ids are declared inline with `@+id/…`, often in the buffer being edited (not yet saved/indexed),
        // so surface those live so `@id/…` completes them immediately.
        if (type == ResourceType.ID) out += liveDeclaredIds()
        return out.toList()
    }

    private val ID_DECL = Regex("""@\+id/([A-Za-z_][\w.]*)""")

    /** `@+id/…` declarations across all open editor buffers (sanitized), for live id completion. */
    private fun liveDeclaredIds(): List<String> =
        openDocuments.values.flatMap { text -> ID_DECL.findAll(text).map { sanitizeResName(it.groupValues[1]) } }.distinct()

    /** Whether the index knows resource `@type/name` (precise). */
    private fun indexHasResource(type: ResourceType, name: String): Boolean =
        indexService.exact<ResourceDeclValue>(AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)).any()

    /** Whether the index has any resource of [type]; used to stay conservative (don't flag invisible types). */
    private fun indexTypeHasAny(type: ResourceType): Boolean =
        indexService.prefix<ResourceDeclValue>(AndroidResourceIndex.id, "${type.rClass}/", limit = 1).any()

    private fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()

    private fun resourceRoots(m: Module): List<Path> =
        m.sourceSets.flatMap { it.contentRoots }.filter { ContentRole.ANDROID_RES in it.roles }.map { Paths.get(it.dir.path) }

    /** The Android module whose `res/` tree contains [file] (an XML resource), or null. */
    private fun moduleForResourceFile(file: Path): Module? {
        val target = file.toAbsolutePath().normalize()
        return modules().firstOrNull { m ->
            m.facets.get(dev.ide.android.support.AndroidFacet.KEY) != null &&
                resourceRoots(m).any { target.startsWith(it.toAbsolutePath().normalize()) }
        }
    }

    /** Builds each per-file analysis target off the live overlay (true working copies, as completion does). */
    private inner class IdeAnalysisEnvironment : AnalysisEnvironment {
        override suspend fun targetFor(file: VirtualFile, needsBindings: Boolean): AnalysisTarget? {
            val path = Paths.get(file.path)
            val module = moduleForFile(path) ?: return null
            val key = path.toAbsolutePath().normalize()
            val text = openDocuments[key] ?: runCatching { key.readText() }.getOrNull() ?: return null
            val analyzer = analyzerFor(module)
            // Tier gate: a SEMANTIC+ pass gets the binding-resolved tree (so analyzers can resolve types/
            // symbols and the one pass also yields the compiler diagnostics); a SYNTAX-only pass gets the
            // cheap syntax tree (no classpath scan, no shadow-file move) the incremental parser produces.
            val doc = EditorDocument(file, docVersion.incrementAndGet(), text)
            val parsed = if (needsBindings && analyzer is JdtSourceAnalyzer) analyzer.parse(file, text)
            else analyzer.incrementalParser.parseFull(doc)
            return JdtAnalysisTarget(file, parsed, parsed.documentVersion, analyzer, indexService, module)
        }

        override fun languageOf(file: VirtualFile): LanguageId? =
            if (file.path.endsWith(".java")) LanguageId("java") else null

        override fun projectScope(): ProjectAnalysisScope = IdeProjectScope()

        override suspend fun applyEdit(edit: WorkspaceEdit): WorkspaceEdit {
            for ((vf, edits) in edit.edits) {
                val p = Paths.get(vf.path).toAbsolutePath().normalize()
                val current = openDocuments[p] ?: runCatching { p.readText() }.getOrDefault("")
                val sb = StringBuilder(current)
                for (e in edits.sortedByDescending { it.offset }) {
                    val s = e.offset.coerceIn(0, sb.length)
                    val en = (e.offset + e.oldLength).coerceIn(s, sb.length)
                    sb.replace(s, en, e.newText.toString())
                }
                openDocuments[p] = sb.toString()
            }
            return edit
        }
    }

    private inner class IdeProjectScope : ProjectAnalysisScope {
        override val modules get() = this@IdeServices.modules()
        override val index get() = indexService
        override fun files(): Sequence<VirtualFile> {
            val out = ArrayList<VirtualFile>()
            for (m in modules()) for (root in sourceRoots(m)) {
                if (!Files.isDirectory(root)) continue
                runCatching { Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.forEach { out += store.vfs.fileFor(it) } } }
            }
            return out.asSequence()
        }
        override suspend fun targetFor(file: VirtualFile): AnalysisTarget =
            // Batch/project sweep: hand analyzers a binding-resolved tree (correctness over the per-keystroke
            // cost, since this path is not the typing path).
            analysisEnvironment.targetFor(file, needsBindings = true) ?: error("no analysis target for ${file.path}")
        override fun checkCanceled() {}
    }

    override fun close() {
        analysisEngine.dispose()
        indexScope.cancel()
        // Dispose the live analyzers so their cached library-jar handles close (no longer platform-registered).
        analyzers.values.toList().also { analyzers.clear() }.forEach { (it as? Disposable)?.let { d -> runCatching { d.dispose() } } }
        runCatching { (indexService as? AutoCloseable)?.close() } // release the on-disk index segment file channels
        platform.dispose()
    }

    private class EditorDocument(
        override val file: VirtualFile,
        override val version: Long,
        override val text: CharSequence,
    ) : DocumentSnapshot {
        override fun length() = text.length
    }

    companion object {
        private val PACKAGE_DECL = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
        private val MAIN_METHOD = Regex("""\bstatic\s+void\s+main\s*\(""")
        private val NAME_ATTR = Regex("""name\s*=\s*"([^"]+)"""")

        /** Cap on a single file's size for find-in-files (skip generated/huge blobs). */
        private const val MAX_SEARCH_FILE_CHARS = 2_000_000

        /** File extensions never scanned by find-in-files. */
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "jar", "aar", "class", "dex", "zip", "apk", "so", "o", "a",
            "keystore", "ks", "jks", "ttf", "otf", "woff", "woff2", "bin", "pdf",
        )

        /**
         * Create the demo at [root] (replacing any existing one) and open it. The default demo is the
         * Android multi-module app (`app → feature → core`). On a desktop the framework `android.jar`
         * is resolved from an installed Android SDK so `android.*` resolves; with no SDK it falls back to a
         * JDK (Java resolves, `android.*` does not).
         */
        fun bootstrapDemo(root: Path): IdeServices {
            if (Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val (platform, store) = openStore(root)
            store.replaceSdks(listOf(detectAndroidSdk() ?: JdkSdkProvider.detect()))
            val types = ModuleTypeRegistry(platform.extensions)
            dev.ide.android.support.SampleAndroidProject.generate(
                store, types.resolve("android-app"), types.resolve("android-lib"), types.resolve("java-lib"),
            )
            store.save()
            return IdeServices(platform, store)
        }

        /** The plain-Java multi-module demo (`app → util → core`) — the fixture for the Java build/run/completion tests. */
        fun bootstrapJavaDemo(root: Path): IdeServices {
            if (Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val (platform, store) = openStore(root)
            store.replaceSdks(listOf(JdkSdkProvider.detect()))
            SampleProject.generate(store, ModuleTypeRegistry(platform.extensions).resolve("java-lib"))
            store.save()
            return IdeServices(platform, store)
        }

        /** An installed Android SDK's `android.jar` as the workspace SDK, or null if none is found. */
        private fun detectAndroidSdk(): SdkData? {
            val sdk = dev.ide.android.support.tools.AndroidSdk.findSdkRoot()
                ?.let { dev.ide.android.support.tools.AndroidSdk.detect(it) } ?: return null
            // android.jar omits java.lang.invoke.StringConcatFactory / LambdaMetafactory; ship the build-tools
            // desugar stubs alongside it on the boot classpath so Java ≥ 9 string-concat (`"a" + b`) and
            // lambdas resolve during analysis (and compilation). Compile-only — see AndroidSdk.coreLambdaStubs.
            val boot = listOf(sdk.androidJar.toString()) +
                listOfNotNull(sdk.coreLambdaStubs.takeIf { Files.exists(it) }?.toString())
            return SdkData("android", boot, sdk.buildToolsDir.toString())
        }

        /** Open an existing workspace at [root] (used by the "Open Project…" picker). */
        fun open(root: Path): IdeServices {
            val (platform, store) = openStore(root)
            if (store.workspace.sdkTable.sdks.isEmpty()) store.replaceSdks(listOf(JdkSdkProvider.detect()))
            return IdeServices(platform, store)
        }

        /**
         * Bootstrap with an explicitly supplied platform boot classpath instead of auto-detecting a JDK.
         * Hosts with no JDK to discover — notably Android/ART, which feeds a bundled `android.jar` — use
         * this. [bootClasspath] is a list of absolute jar paths. Regenerates the demo project at [root]
         * when [generateDemo]; otherwise opens whatever is there (seeding the SDK only if there is none).
         * Takes plain strings (not the internal `SdkData`) so callers needn't depend on the model API.
         */
        fun bootstrapWithBootClasspath(
            root: Path,
            bootClasspath: List<String>,
            sdkName: String = "android",
            generateDemo: Boolean = true,
            /** App `nativeLibraryDir` holding the extracted `libaapt2.so`/`libzipalign.so` prebuilts (on-device). */
            androidToolsDir: Path? = null,
            /** Debug keystore copied out of app assets (on-device). With [androidToolsDir], enables on-device assembly. */
            debugKeystore: Path? = null,
            /** On-device `DexClassLoader` runner (from :ide-android) — enables running a Java console app on ART. */
            dexRunner: DexRunner? = null,
            /** The device's `Build.VERSION.SDK_INT` — min-api the Java dex-run targets. */
            deviceApiLevel: Int = 21,
            /** On-device APK installer (from :ide-android) — enables the android Run (build + install + launch). */
            apkInstaller: ApkInstaller? = null,
        ): IdeServices {
            if (generateDemo && Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val (platform, store) = openStore(root)
            val sdk = SdkData(sdkName, bootClasspath, buildToolsPath = null)
            if (generateDemo || store.workspace.sdkTable.sdks.isEmpty()) store.replaceSdks(listOf(sdk))
            if (generateDemo) {
                // Java 8 level: a bundled android.jar is non-modular, and JDT's DOM ASTParser (the analysis
                // path) requires a modular system library (java.base) at compliance ≥ 9. Java 8 skips that
                // check, so completion and analysis work on-device. (Modern levels need the modular-JDK download.)
                // The on-device demo is an Android app, resolving against the bundled android.jar fed above.
                val types = ModuleTypeRegistry(platform.extensions)
                dev.ide.android.support.SampleAndroidProject.generate(
                    store, types.resolve("android-app"), types.resolve("android-lib"), types.resolve("java-lib"),
                    languageLevel = dev.ide.model.LanguageLevel.JAVA_8,
                )
                store.save()
            }
            // On-device the launcher supplies the native build tools + keystore (the android.jar is the first
            // boot-classpath entry); the in-process Android build (AndroidBuildSystem.inProcess) runs off these.
            val androidTools = if (androidToolsDir != null && debugKeystore != null && bootClasspath.isNotEmpty())
                AndroidDeviceTools(Paths.get(bootClasspath.first()), androidToolsDir, debugKeystore, deviceApiLevel) else null
            return IdeServices(platform, store, androidTools, dexRunner, apkInstaller)
        }

        private fun openStore(root: Path): Pair<PlatformCore, ProjectModelStore> {
            val platform = PlatformCore()
            val moduleTypes = ModuleTypeRegistry(platform.extensions)
            moduleTypes.register(JavaLibModuleType, PluginId("java-support"))
            val codecs = FacetCodecRegistry()
            // android-support: register android-app/-lib module types + the AndroidFacet codec so
            // `module.toml` files of type android-* load with a resolvable type and a decodable facet.
            dev.ide.android.support.AndroidSupport.register(moduleTypes, codecs)
            // platform.syntheticClass: the light Android `R` (resolved from real resources) so R.layout.*
            // etc. complete AND analyze before a build. Other generated-code stand-ins can register here too.
            platform.extensions.register(SYNTHETIC_CLASS_EP, dev.ide.android.support.AndroidRClassProvider(), PluginId("android-support"))
            platform.extensions.register(SYNTHETIC_CLASS_EP, dev.ide.android.support.AndroidBuildConfigProvider(), PluginId("android-support"))
            // Kotlin interop: a module's top-level `fun`/`val` become a `<File>Kt` facade and its classes/
            // objects become types, so Java code (and JDT completion/analysis) resolves them before a build.
            platform.extensions.register(SYNTHETIC_CLASS_EP, dev.ide.lang.kotlin.synthetic.KotlinSyntheticClassProvider(), PluginId("kotlin-support"))
            // platform.fileIcon: the built-in classifier + the Android plugin's res/assets/manifest icons.
            val fileIcons = FileIconRegistry(platform.extensions)
            fileIcons.register(DefaultFileIconProvider, PluginId("platform"))
            dev.ide.android.support.AndroidSupport.registerIcons(fileIcons)
            // platform.projectTemplate: the Create-Project gallery — built-in Java + Kotlin templates + Android plugin's.
            val templates = ProjectTemplateRegistry(platform.extensions)
            templates.register(JavaConsoleAppTemplate, PluginId("java-support"))
            templates.register(JavaLibraryTemplate, PluginId("java-support"))
            templates.register(KotlinConsoleAppTemplate, PluginId("kotlin-support"))
            templates.register(KotlinLibraryTemplate, PluginId("kotlin-support"))
            dev.ide.android.support.AndroidSupport.registerTemplates(templates)
            val store = ProjectModel.open(root, platform, codecs)
            return platform to store
        }

        /** Desktop default SDK: an installed Android SDK's `android.jar` if present, else a detected JDK. */
        internal fun defaultDesktopSdk(): SdkData = detectAndroidSdk() ?: JdkSdkProvider.detect()

        /**
         * Create a brand-new project at [root] from the template [templateId], seeding [sdk] and writing
         * the model + sources, then open it. The reusable core behind [ProjectManager.create], generalizing
         * the `bootstrap*` demos: the template (not a hard-coded `Sample*Project`) authors the project,
         * and the host injects the SDK, [languageLevel] (JAVA_17 desktop / JAVA_8 on-device) and [androidTools].
         */
        fun createProjectAt(
            root: Path,
            templateId: String,
            args: Map<String, String>,
            sdk: SdkData,
            languageLevel: LanguageLevel,
            androidTools: AndroidDeviceTools? = null,
            dexRunner: DexRunner? = null,
            apkInstaller: ApkInstaller? = null,
        ): IdeServices {
            Files.createDirectories(root)
            val (platform, store) = openStore(root)
            store.replaceSdks(listOf(sdk))
            val template = ProjectTemplateRegistry(platform.extensions).byId(TemplateId(templateId))
                ?: error("Unknown project template '$templateId'")
            template.generate(ScaffoldImpl(store, languageLevel), TemplateArgs(args))
            store.save()
            return IdeServices(platform, store, androidTools, dexRunner, apkInstaller)
        }

        /** Open the existing workspace at [root] (seeding [sdk] only if it has none). The [ProjectManager.open] core. */
        fun openAt(root: Path, sdk: SdkData, androidTools: AndroidDeviceTools? = null, dexRunner: DexRunner? = null, apkInstaller: ApkInstaller? = null): IdeServices {
            val (platform, store) = openStore(root)
            if (store.workspace.sdkTable.sdks.isEmpty()) store.replaceSdks(listOf(sdk))
            return IdeServices(platform, store, androidTools, dexRunner, apkInstaller)
        }
    }
}

/** A surfaced content root with the metadata the tree needs: where it is, its source-set, its roles. */
data class TreeRootInfo(val path: Path, val sourceSetName: String, val roles: Set<ContentRole>)

/** Content roles surfaced in the project tree (code + resources + assets); see [IdeServices.treeRoots]. */
private val TREE_ROOT_ROLES = setOf(
    ContentRole.SOURCE, ContentRole.GENERATED, ContentRole.RESOURCE, ContentRole.ANDROID_RES, ContentRole.ASSETS,
)

/** The per-file analysis context the engine consumes: live DOM + the module's resolver + the index. */
private class JdtAnalysisTarget(
    override val file: VirtualFile,
    override val parsed: ParsedFile,
    override val documentVersion: Long,
    override val resolver: SourceAnalyzer,
    override val index: dev.ide.index.IndexService,
    override val module: Module,
) : AnalysisTarget {
    override fun checkCanceled() {}
}

/** Drives a `suspend` SPI call to completion synchronously (the analyzer never actually suspends). */
private fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

/** Whether the Android platform sources (parameter names + javadoc for `android.*`) are installed/obtainable. */
data class AndroidSourcesInfo(val platform: String, val installed: Boolean, val downloadable: Boolean)
