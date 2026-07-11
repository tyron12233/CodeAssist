package dev.ide.core

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.AnalysisProfile
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.Analyzer
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.QUICK_FIX_PROVIDER_EP
import dev.ide.analysis.WorkspaceEdit
import dev.ide.analysis.impl.AnalysisEngine
import dev.ide.analysis.impl.AnalysisEnvironment
import dev.ide.android.support.AndroidBuildConfigProvider
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidRClassProvider
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.AndroidVariants
import dev.ide.android.support.AndroidViewBindingProvider
import dev.ide.android.support.SampleAndroidProject
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttrEntry
import dev.ide.android.support.metadata.AttrsXmlParser
import dev.ide.android.support.metadata.SdkMetadataCodec
import dev.ide.android.support.metadata.StyleableEntry
import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.ColorEntry
import dev.ide.android.support.preview.ColorResources
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.KeystoreRegistry
import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.impl.JavaBlockMapping
import dev.ide.build.engine.DexRunner
import dev.ide.core.IdeServices.Companion.PARSER_WARMUP_MIN_FREE_BYTES
import dev.ide.core.IdeServices.Companion.openStore
import dev.ide.core.IdeServices.Companion.registerActiveEnginePlugins
import dev.ide.core.IdeServices.Companion.registerStaticPlugins
import dev.ide.core.actions.BuiltInActions
import dev.ide.core.completion.BufferWordsContributor
import dev.ide.core.completion.CompletionEngine
import dev.ide.core.completion.CompletionOptions
import dev.ide.core.completion.CompletionStats
import dev.ide.core.completion.PostfixContributor
import dev.ide.core.services.BlockService
import dev.ide.core.services.BuildService
import dev.ide.core.services.DependencyService
import dev.ide.core.services.ModuleService
import dev.ide.core.services.RunCapture
import dev.ide.core.services.SearchService
import dev.ide.core.services.SigningService
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.core.templates.CalculatorSampleTemplate
import dev.ide.core.templates.JavaConsoleAppTemplate
import dev.ide.core.templates.JavaLibraryTemplate
import dev.ide.core.templates.KotlinConsoleAppTemplate
import dev.ide.core.templates.KotlinLibraryTemplate
import dev.ide.core.templates.NotesSampleTemplate
import dev.ide.core.templates.WeatherSampleTemplate
import dev.ide.deps.ConflictPolicy
import dev.ide.index.INDEX_EP
import dev.ide.index.IndexItemState
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.AnalysisResult
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.jdt.JdtLanguageBackend
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.jdt.context.ModuleCompilationContext
import dev.ide.lang.jdt.index.JavaClassLocatorIndex
import dev.ide.lang.jdt.index.JavaClassNamesIndex
import dev.ide.lang.jdt.index.JavaMainIndex
import dev.ide.lang.jdt.index.JavaMembersByOwnerIndex
import dev.ide.lang.jdt.index.JavaMembersIndex
import dev.ide.lang.jdt.index.JavaPackageTypesIndex
import dev.ide.lang.jdt.index.JavaPackagesIndex
import dev.ide.lang.jdt.index.JavaSourceAnnotationIndex
import dev.ide.lang.jdt.index.JavaSourceDocIndex
import dev.ide.lang.jdt.index.JavaSourceIndexer
import dev.ide.lang.jdt.index.JavaSourceSubtypeIndex
import dev.ide.lang.jdt.index.JavaSourceSymbolsIndex
import dev.ide.lang.jdt.rename.JdtRename
import dev.ide.lang.jdt.synthetic.SyntheticJavaSource
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.kotlin.compile.DefaultKotlinPluginLoader
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import dev.ide.lang.kotlin.compile.KotlinPluginLoader
import dev.ide.lang.kotlin.completion.KotlinPostfixTemplates
import dev.ide.lang.kotlin.index.BinaryAnnotationIndex
import dev.ide.lang.kotlin.index.BinarySubtypeIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinMainIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinSourceAnnotationIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.synthetic.KotlinSyntheticClassProvider
import dev.ide.lang.resolve.SourceDocProvider
import dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.lang.xml.XmlParsedFile
import dev.ide.lang.xml.XmlSourceAnalyzer
import dev.ide.lang.xml.completion.XmlCompletion
import dev.ide.lang.xml.completion.XmlContextScanner
import dev.ide.lang.xml.hints.XmlResourceValueResolver
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.IconTarget
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.model.impl.DefaultFileIconProvider
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.FileIconRegistry
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.model.impl.SdkData
import dev.ide.model.impl.jdk.CorePlatformProvider
import dev.ide.model.impl.jdk.JdkSdkProvider
import dev.ide.model.PlatformKind
import dev.ide.model.SdkResolution
import dev.ide.model.module
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateId
import dev.ide.platform.Disposable
import dev.ide.platform.EngineCanceledException
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceDescriptor
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.log.Log
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingsPage
import dev.ide.plugin.impl.ActionManager
import dev.ide.preview.impl.CustomViewRuntime
import dev.ide.preview.impl.RealViewRuntime
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.IndexWorkItem
import dev.ide.ui.backend.IndexWorkState
import dev.ide.ui.backend.PreviewProgress
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

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
    /** Java 9+ desugar stubs (`core-lambda-stubs.jar`: `StringConcatFactory`/`LambdaMetafactory`), part of the
     *  compile platform so a Java 9+ build's string concatenation/lambdas resolve at compile time (D8 desugars
     *  the resulting invokedynamic). `android.jar` omits them; on ART they ship as a bundled asset. */
    val desugarStubs: List<Path> = emptyList(),
    /** On-device R8 shrinker that runs R8 in a forked command-line VM (`dalvikvm64 -Xmx…`) so its whole-program
     *  pass gets a heap larger than the app's `largeHeap` cap — the fix for the release/minify OOM. Null falls
     *  back to the in-process R8 ([dev.ide.android.support.tools.R8InProcessShrinker]). Supplied by :ide-android,
     *  which bundles R8's dexes + discovers the launcher; the forked shrinker self-falls-back if forking is
     *  unavailable on the device. */
    val r8Shrinker: dev.ide.android.support.tools.Shrinker? = null,
    /** On-device D8 dexer for the dex MERGE step (the debug-path memory peak), run in a forked VM so it gets a
     *  heap above the app cap. Null → the in-process merge. Supplied by :ide-android; self-falls-back. */
    val r8MergeDexer: dev.ide.android.support.tools.Dexer? = null,
    /** Max class-dex merged in one batch on a large app (the "Dex merge batch size" setting). Read per build so
     *  a change applies on the next build; defaults to [BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT]. */
    val mergeChunkProvider: () -> Int = { BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT },
)

/**
 * Installs (and then launches) a freshly built APK: the on-device "Run" for an android-app. Supplied by
 * :ide-android (it needs Android's `PackageInstaller` + the OS install-confirmation UI); null on the
 * desktop, where the android task stops at producing the signed artifact. [installAndLaunch] returns once
 * the installation is initiated and streams progress + the eventual launch to [log].
 */
interface ApkInstaller {
    suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean
}

/** The status of running a Compose `@Preview` through the interpreter: [ok] = interpretable/rendered. */
data class PreviewRunResult(val ok: Boolean, val message: String)

/** A lowered `@Preview` ready to render: the preview function + the file's program for its source calls, plus
 *  the file's source classes/objects/enums (which the interpreter materializes — they aren't compiled). When
 *  the preview takes a `@PreviewParameter`, [parameter] describes the provider the renderer feeds it from. */
data class LoweredComposePreview(
    val entry: ResolvedFunction,
    val program: Map<String, ResolvedFunction>,
    val classes: List<ResolvedClass> = emptyList(),
    val parameter: LoweredPreviewParameter? = null,
)

/** A `@PreviewParameter` provider resolved for rendering. [providerClass] is the lowered source class when the
 *  provider is project source (the interpreter instantiates it); otherwise [providerFqn] names a library class
 *  the renderer loads reflectively. [limit] caps how many of the provider's sample values are rendered. */
data class LoweredPreviewParameter(
    val providerSimpleName: String,
    val providerFqn: String?,
    val providerClass: ResolvedClass?,
    val limit: Int,
)

/**
 * The library inputs an on-device Compose preview needs to dispatch against the project's real libraries:
 * the module compile-classpath [jars] (transitive), the bundled [androidJar] (boot classpath for desugaring,
 * null if none), the module's [minApi], and a content-stable [fingerprint] (sorted jar paths + sizes) the
 * launcher keys its dex/classloader cache on. See `IdeServices.composePreviewLibs`.
 */
data class ComposePreviewLibs(
    val jars: List<Path>,
    val fingerprint: String,
    val androidJar: Path?,
    val minApi: Int,
    /** Base dir for the launcher's dex/oat cache (per-[fingerprint] subdir lives under here). */
    val cacheDir: Path,
)

/**
 * Renders a lowered `@Preview` composable into the real Compose runtime (the interpreter half lives in
 * :interp-core / :ide-android). Supplied by :ide-android (it needs the real `androidx.compose.runtime` + a
 * composition surface); null on the desktop / until wired, where preview "runs" report only interpretability.
 */
interface ComposePreviewRunner {
    suspend fun render(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
    ): PreviewRunResult
}

/** WORKSPACE-scoped: this engine's [EngineContext] (the shared-infrastructure surface). Registered on the
 *  engine's own workspace container so app-global service factories can resolve the per-project engine through
 *  the scope (MODULE → WORKSPACE) rather than closure-capturing it. */
private val ENGINE_CONTEXT = ServiceKey<EngineContext>("ide.engineContext")

/** APPLICATION-scoped: the warm K2 compiler shared across every opened project. */
private val KOTLIN_JVM_COMPILER = ServiceKey<KotlinJvmCompiler>("ide.kotlin.jvmCompiler")

/** MODULE-scoped: the per-module source analyzer for each language. */
private val ANALYZER_JAVA = ServiceKey<SourceAnalyzer>("ide.analyzer.java")
private val ANALYZER_KOTLIN = ServiceKey<SourceAnalyzer>("ide.analyzer.kotlin")
private val ANALYZER_XML = ServiceKey<SourceAnalyzer>("ide.analyzer.xml")

/** WORKSPACE-scoped: this engine's decomposed concern services, resolved from the workspace container. */
private val SIGNING_SERVICE = ServiceKey<SigningService>("ide.service.signing")
private val SEARCH_SERVICE = ServiceKey<SearchService>("ide.service.search")
private val BLOCK_SERVICE = ServiceKey<BlockService>("ide.service.blocks")
private val ACTION_MANAGER = ServiceKey<ActionManager>("ide.service.actions")
private val DEPENDENCY_SERVICE = ServiceKey<DependencyService>("ide.service.dependencies")
private val MODULE_SERVICE = ServiceKey<ModuleService>("ide.service.modules")
private val BUILD_SERVICE = ServiceKey<BuildService>("ide.service.build")

/**
 * APPLICATION-scoped shared toolchain services — reachable with no project open (the picker's Settings &
 * Tools hub). Registered on the shared application container, so there is ONE SDK download queue and ONE
 * keystore registry across every project (their on-disk artifacts already live under the shared home dir).
 * [ProjectManager] registers them eagerly so the picker can resolve them before any engine exists; an
 * engine [registerScopedServices] registers them idempotently too, for the manager-less (test) path.
 * `internal` (not file-private) so [ProjectManager] in the same package can register/resolve them.
 */
internal val APP_SDK_MANAGER = ServiceKey<SdkManagerService>("ide.app.sdkManager")
internal val APP_KEYSTORE_REGISTRY = ServiceKey<KeystoreRegistry>("ide.app.keystoreRegistry")

/** A file above this size, or with a NUL byte in its first block, is treated as non-text: the editor shows
 *  a placeholder instead of loading it (see [dev.ide.core.backend.FileBackend.readFile]), and an editor
 *  overlay is never written back over it ([IdeServices.save]/[IdeServices.flushOpenDocuments]). Mirrors
 *  FileBackend's own MAX_TEXT_BYTES so the read and write sides agree on what "text" is. */
private const val MAX_EDITOR_TEXT_BYTES = 5_000_000L

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
    /** Optional platform runtime that renders *live* custom views in the layout preview (device dex / desktop shim). */
    private val customViewRuntime: CustomViewRuntime? = null,
    /** Optional device-only runtime that renders the layout with the REAL Android view stack (layoutlib-on-device). */
    private val realViewRuntime: RealViewRuntime? = null,
    /** Non-null only on-device (supplied by :ide-android): loads runtime (non-bundled) Kotlin compiler plugins
     *  via D8-dex + `DexClassLoader`. Null → the desktop `DefaultKotlinPluginLoader` (URLClassLoader). */
    private val kotlinPluginLoader: KotlinPluginLoader? = null,
    /**
     * Where the resolved-dependency cache lives. Null → per-project (`<workspace>/.platform/...`); a host
     * passes a shared app-level dir (the projects-root parent) so every project reuses one another's
     * downloaded jars/AARs — a second project pulling the same Compose graph hits disk, not the network.
     */
    private val sharedCachesRoot: Path? = null,
    /** Headless BUILD-ONLY engine (the `:build` daemon): skip the editor cold-start (symbol index + the two
     *  Kotlin warm-ups) on open. A build needs only the model/classpath/compilers, so dropping that baseline
     *  frees heap for the dexer/R8 (the build's real memory ceiling). See docs/build-process-isolation.md. */
    private val buildOnly: Boolean = false,
    /** The application environment whose substrate this engine's per-project platform parents. App-level
     *  extension callbacks that fire outside any service scope (command actions, the synthetic-R provider, the
     *  XML resource host) resolve the open engine through [ApplicationEnvironment.activeEngine], which the
     *  backend points at this engine on project swap. */
    private val env: ApplicationEnvironment,
) : AutoCloseable {

    // Phase-0 build-process-isolation instrumentation (docs/build-process-isolation.md): the most recent
    // build/run and project-open heap peaks. [lastBuildPeak] is read by the analytics bridge to attach to
    // build_result; both are logged on the `ide.mem` tag for on-device inspection via the Logs viewer.
    // Declared FIRST so the cold-start coroutine (which logs via memLog) can never read it before init.
    @Volatile
    internal var lastOpenPeak: MemSample? = null
    private val memLog = Log.logger("ide.mem")

    /** The build/run seam ([BuildRunner]): today an in-process runner; a future remote runner swaps in for
     *  the separate-process build (docs/build-process-isolation.md). [dev.ide.core.backend.BuildBackend]
     *  routes all build/run calls through this rather than calling the build methods directly. `by lazy`
     *  defers creation past construction so it never participates in field init-order. Public so the
     *  on-device build daemon (:ide-android BuildDaemonService) can drive a headless build through it. */
    val buildRunner: BuildRunner by lazy { InProcessBuildRunner(build) }

    // Language backends are contributed through the `platform.languageBackend` EP (registered ONCE, app-global,
    // in [registerStaticPlugins]) and selected per file by matching the file's LanguageId against each
    // backend's `languages`. Java (JDT) is registered first as the default; this is a QUERY of the (hierarchical)
    // registry, so it sees the app-global contributions.
    private val languageBackends: List<LanguageBackend> =
        platform.extensions.extensions(LANGUAGE_BACKEND_EP)

    /** The unified completion pipeline (language backend + cross-cutting + plugin contributors, ranked by
     *  weighers). Built-in contributors are registered app-global (see [registerStaticPlugins]); plugins add
     *  their own. This engine just queries the registry per request. */
    private val completionEngine: CompletionEngine = CompletionEngine(platform.extensions)

    /** Per-project completion-acceptance counters behind the app-global `platform.stats` weigher (which
     *  reaches them through [ApplicationEnvironment.activeEngine]). */
    val completionStats: CompletionStats by lazy {
        CompletionStats(store.rootPath.resolve(".platform/completion-stats.properties"))
    }

    /** Record that the user accepted the completion item labeled [label] (see [CompletionStats]). */
    fun noteCompletionAccepted(label: String) =
        completionStats.noteAccepted(CompletionStats.keyOf(label))

    /** The backend whose `languages` contains [language], or the first (Java/JDT) as a fallback. */
    private fun backendFor(language: LanguageId): LanguageBackend =
        languageBackends.firstOrNull { language in it.languages } ?: languageBackends.first()

    /** Language id for ProGuard/R8 keep-rule files. No backend is registered for it: editing falls back to
     *  plain text and the diagnostics engine (which dispatches by language) runs nothing, so a `.pro` file is
     *  never analysed as Java. */
    private val PROGUARD_LANGUAGE_ID = LanguageId("proguard")

    /** Language id for Markdown documents. Like [PROGUARD_LANGUAGE_ID] no backend is registered, so a `.md`
     *  file (even one inside a source root) is edited as plain text and never analysed as Java; its rich-text
     *  rendering is a UI-side Preview, not an analysis backend. */
    private val MARKDOWN_LANGUAGE_ID = LanguageId("markdown")

    /** The language of [file] by extension: `.xml` → xml, `.kt` → kotlin, `.pro` → proguard, `.md` → markdown
     *  (the last two have no analysis backend, so they're edited as plain text without being mis-parsed as
     *  Java), everything else → java. */
    private fun languageFor(file: Path): LanguageId {
        val name = file.fileName?.toString() ?: return LanguageId("java")
        return when {
            name.endsWith(".xml") -> XmlLanguageBackend.LANGUAGE_ID
            name.endsWith(".kt") || name.endsWith(".kts") -> KotlinLanguageBackend.LANGUAGE_ID
            // ProGuard/R8 keep-rule files have no language backend; routing them off "java" keeps the JDT
            // analyzer from flagging the file as broken Java (the diagnostics engine dispatches by language).
            name.endsWith(".pro") -> PROGUARD_LANGUAGE_ID
            name.endsWith(".md") || name.endsWith(".markdown") -> MARKDOWN_LANGUAGE_ID
            else -> LanguageId("java")
        }
    }

    private fun isKotlin(file: Path): Boolean =
        file.fileName?.toString()?.let { it.endsWith(".kt") || it.endsWith(".kts") } == true

    private val docVersion = AtomicLong(0)

    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The workspace index (class names, packages, members, source symbols). Built in the background. The index
     *  extensions are contributed app-global (see [registerStaticPlugins]); this just queries them. */
    val indexService: IndexService = run {
        IndexServiceImpl(
            platform.extensions.extensions(INDEX_EP),
            // Static (SDK + library) segments are content-addressed (identical jars index to identical
            // segments), so share them across projects under the host's shared cache root (like the dex and
            // Maven caches) instead of re-indexing the same AndroidX/Compose/stdlib jars per project. The
            // in-memory source side stays per-project regardless of where this points.
            (sharedCachesRoot ?: store.rootPath).resolve("caches").resolve("index"),
            // On-device (androidTools present) ART has a tight heap, so use a smaller hot-block cache; desktop keeps the default.
            blockCacheBytes = if (androidTools != null) IndexServiceImpl.CONSTRAINED_BLOCK_CACHE_BYTES
            else IndexServiceImpl.DEFAULT_BLOCK_CACHE_BYTES,
            // The source side IS per-project (not shareable), so persist its per-file partitions under the
            // project's own caches — a re-open then re-parses only the source files that changed since last time.
            sourceCacheRoot = store.rootPath.resolve(".platform/caches/source-index"),
        )
    }

    /** Resolves a tree node to an icon id via the registered `platform.fileIcon` providers. */
    val fileIcons: FileIconRegistry = FileIconRegistry(platform.extensions)

    /** The icon id for [target] (default classifier + plugins), or null if no provider is registered. */
    fun iconFor(target: IconTarget): String? = fileIcons.resolve(target)

    private val _indexStatus = MutableStateFlow(IndexUiStatus())
    val indexStatus: StateFlow<IndexUiStatus> get() = _indexStatus

    // Live stage of the real-view layout render (relink → render), for the floating status chip; null = idle.
    private val _realViewProgress = MutableStateFlow<PreviewProgress?>(null)
    val realViewProgress: StateFlow<PreviewProgress?> get() = _realViewProgress

    // The narrow shared-infrastructure view ([EngineContext]) handed to this engine's decomposed concern
    // services, so each one depends on the surface it needs rather than the whole engine. An inner object so
    // it can reach private helpers (projectOf/invalidateAnalyzers/resyncIndex) without widening their
    // visibility; the getters are lazy, so referencing later-declared fields (keystoreRegistry/sdkManager) is
    // fine — they resolve only when a service is first used, long after construction.
    private val engineContext: EngineContext = object : EngineContext {
        override val store get() = this@IdeServices.store
        override val platform get() = this@IdeServices.platform
        override val workspaceRoot get() = this@IdeServices.workspaceRoot
        override val sharedCachesRoot get() = this@IdeServices.sharedCachesRoot
        override val indexService get() = this@IdeServices.indexService
        override val keystoreRegistry get() = this@IdeServices.keystoreRegistry
        override val sdkManager get() = this@IdeServices.sdkManager
        override val dependencies get() = this@IdeServices.dependencies
        override val kotlinJvmCompiler get() = this@IdeServices.kotlinJvmCompiler
        override val compileBootClasspath get() = this@IdeServices.compileBootClasspath
        override fun bootClasspathFor(module: Module) = this@IdeServices.bootClasspathFor(module)
        override val androidTools get() = this@IdeServices.androidTools
        override val dexRunner get() = this@IdeServices.dexRunner
        override val apkInstaller get() = this@IdeServices.apkInstaller
        override fun modules() = this@IdeServices.modules()
        override fun projectOf(module: Module) = this@IdeServices.projectOf(module)
        override fun moduleRoot(module: Module) = this@IdeServices.moduleRoot(module)
        override fun moduleBuildClosure(module: Module) = this@IdeServices.moduleBuildClosure(module)
        override fun sourceRoots(module: Module) = this@IdeServices.sourceRoots(module)
        override fun treeRoots(module: Module) = this@IdeServices.treeRoots(module)
        override fun overlayText(path: Path) = this@IdeServices.openDocuments[path]
        override fun flushOpenDocuments() = this@IdeServices.flushOpenDocuments()
        override fun ensureKotlinStdlib() = this@IdeServices.ensureKotlinStdlib()
        override fun parse(file: Path, text: String) = this@IdeServices.parse(file, text)
        override fun buildAnalyzer(module: Module, language: LanguageId) = this@IdeServices.buildAnalyzer(module, language)
        override fun resourceRepo(module: Module) = this@IdeServices.resourceRepo(module)
        override fun invalidateSyntheticClasses() = this@IdeServices.invalidateSyntheticClasses()
        override fun projectPref(key: String) = this@IdeServices.projectPref(key)
        override fun setProjectPref(key: String, value: String) = this@IdeServices.setProjectPref(key, value)
        override fun activeVariant(module: Module) = this@IdeServices.activeVariant(module)
        override fun listVariants(module: Module) = this@IdeServices.listVariants(module)
        override fun setActiveVariant(module: Module, variantName: String) = this@IdeServices.setActiveVariant(module, variantName)
        override fun invalidateAnalyzers() = this@IdeServices.invalidateAnalyzers()
        override fun resyncIndex() = this@IdeServices.resyncIndex()
        override val events get() = this@IdeServices.events
    }

    /** Callbacks fired (on the mutating thread) when the workspace CONFIGURATION changed (model commit,
     *  variant/settings/SDK change): a general seam for out-of-process consumers. Declared BEFORE [events]
     *  so a model commit during construction can never observe it uninitialized. */
    private val configurationListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * The workspace change-notification spine (see [WorkspaceEventHub]). Every mutation this engine performs
     * publishes a typed event here, and the invalidation chains (analyzers/index/synthetics/overlays) run as
     * its subscribers, so in-process consumers and the out-of-process engines' hint fan-out observe one
     * ordered stream. The [WorkspaceEventHub.Reactions] impl is an inner object for the same reason
     * [engineContext] is: it reaches the private helpers without widening their visibility.
     */
    internal val events: WorkspaceEventHub = WorkspaceEventHub(store, object : WorkspaceEventHub.Reactions {
        override fun invalidateAnalyzers() = this@IdeServices.invalidateAnalyzers()
        override fun invalidateSyntheticClasses() = this@IdeServices.invalidateSyntheticClasses()
        override fun resyncIndex() = this@IdeServices.resyncIndex()
        override fun reindexSourceAsync(path: Path) {
            indexScope.launch {
                runCatching {
                    val text = openDocuments[path] ?: path.readText()
                    indexService.reindexSource(path, text)
                }
            }
        }
        override fun dropJavaBindingCaches() {
            store.liveModuleContainers()
                .forEach { (it.peekService(ANALYZER_JAVA) as? JdtSourceAnalyzer)?.invalidateBindingCache() }
        }
        override fun dropOverlaysUnder(root: Path) = this@IdeServices.dropOverlaysUnder(root)
        override fun rekeyOverlays(from: Path, to: Path) = this@IdeServices.rekeyOverlays(from, to)
        override fun isResourcePath(path: Path) = this@IdeServices.isResourcePath(path)
        override fun configurationChanged() {
            // Seam for out-of-process engines: configuration-change hints hang off here (a no-op until a
            // listener is attached).
            configurationListeners.forEach { runCatching { it() } }
        }
    })

    /** Register a configuration-change callback (deps/variant/SDK/settings); returns a [Disposable] that
     *  unregisters it. */
    fun addConfigurationListener(listener: () -> Unit): Disposable {
        configurationListeners.add(listener)
        return Disposable { configurationListeners.remove(listener) }
    }

    /** WORKSPACE-scoped signing service (keystore registry + per-build-type assignment). */
    internal val signing: SigningService get() = store.workspaceContainer.getService(SIGNING_SERVICE)

    /** WORKSPACE-scoped symbol/member/full-text search service. */
    internal val search: SearchService get() = store.workspaceContainer.getService(SEARCH_SERVICE)

    /** WORKSPACE-scoped projectional (block) editor service. */
    internal val blocks: BlockService get() = store.workspaceContainer.getService(BLOCK_SERVICE)

    /** WORKSPACE-scoped action surface (resolves/invokes the contributed toolbar/menu/palette actions). */
    internal val actions: ActionManager get() = store.workspaceContainer.getService(ACTION_MANAGER)

    /** WORKSPACE-scoped dependency management (Maven add/resolve, platforms, local libs, repositories). */
    internal val dependencies: DependencyService get() = store.workspaceContainer.getService(DEPENDENCY_SERVICE)

    /** WORKSPACE-scoped module configuration + management (config, source sets/roots, build features, modules). */
    internal val moduleService: ModuleService get() = store.workspaceContainer.getService(MODULE_SERVICE)

    /** WORKSPACE-scoped build + run orchestration (the BuildRunner's in-process arm delegates here). */
    internal val build: BuildService get() = store.workspaceContainer.getService(BUILD_SERVICE)

    /** Set the Maven version-conflict policy (delegates to the dependency service). Kept here so the settings
     *  surface can reach it through the engine without depending on the service type. */
    fun setConflictPolicy(policy: ConflictPolicy) = dependencies.setConflictPolicy(policy)

    /** Compile [moduleName] and run its detected `main`, capturing stdout + exit code + compile diagnostics —
     *  the programmatic run used by the Learn exercise checker over a scratch project. Does not touch the
     *  interactive build/run console state. See [dev.ide.core.services.BuildService.runAndCapture]. */
    suspend fun runAndCapture(moduleName: String, stdin: String = "", timeoutMs: Long = 60_000): RunCapture =
        build.runAndCapture(moduleName, stdin, timeoutMs)

    init {
        // Publish this engine's per-engine container services (the K2 compiler + this engine's EngineContext)
        // before anything resolves them. The reusable concern-service descriptors, the built-in actions, the
        // synthetic-R provider and the XML resource host are all registered ONCE, app-global (in
        // [registerStaticPlugins]), and resolve THIS engine via the scope / [ApplicationEnvironment.activeEngine].
        registerScopedServices()
        // Provision kotlin-stdlib as a real project dependency (bundled jar, never the host runtime) before
        // anything reads a Kotlin module's classpath.
        runCatching { ensureKotlinStdlib() }
        indexService.observeStatus { s ->
            _indexStatus.value = IndexUiStatus(
                building = s.building,
                message = s.message,
                fraction = s.fraction,
                phase = s.phase,
                items = s.items.map { item ->
                    IndexWorkItem(
                        label = item.label,
                        state = when (item.state) {
                            IndexItemState.PENDING -> IndexWorkState.PENDING
                            IndexItemState.ACTIVE -> IndexWorkState.ACTIVE
                            IndexItemState.DONE -> IndexWorkState.DONE
                        },
                    )
                },
                processed = s.processed,
                total = s.total,
            )
        }
        // Cold-start sequencing — memory safety vs editor latency. The index build and the two Kotlin warm-ups
        // are each heavy, and the warm-ups deliberately RETAIN their environments (KotlinEnvironmentKeepAlive),
        // so firing all THREE at project open stacks their peaks on top of each other. On a tight-heap device
        // (ART/emulator) that storm drove the whole system into the kernel low-memory killer mid-index: the app
        // was SIGKILLed (no catchable exception — the index's runCatching guards can't stop an OS kill), which
        // users saw as "the app crashes after indexing" with the index dialog frozen on whatever file was
        // current. So on device we do NOT fire all three at once. The COMPILER warm-up (the heap-heavy one,
        // needed only for the first Run/build) stays sequenced AFTER the index. But the PARSER warm-up sits on
        // the EDITOR critical path — the highlighting daemon's first FOLDS/SEMANTIC/DIAGNOSTICS pass parses
        // through KotlinParserHost — so gating it behind a multi-second index build means a file opened during
        // indexing pays the cold KotlinCoreEnvironment standup on the engine thread (folding/coloring stalls).
        // It's also the LIGHTER of the two. So we overlap ONLY the parser warm-up with the index (two peaks, not
        // three — strictly less than the storm that OOM'd), still heap-guarded so a tight device skips it and
        // falls back to the lazy standup. Desktop has ample heap and overlaps both warm-ups with the index.
        if (buildOnly) {
            // Headless build engine (the :build daemon): skip the editor cold-start (symbol index + Kotlin
            // warm-ups). A build uses only the model/classpath/compilers, never the editor index, and the
            // warm-ups are editor-latency optimizations — dropping them frees that baseline for the dexer/R8.
            memLog.info("build-only engine: skipping editor index + Kotlin warm-ups")
        } else if (androidTools != null) {
            val hasKotlin = projectHasKotlin()
            // Parser warm-up OVERLAPS the index build (editor critical path; see the sequencing note above).
            // Heap-guarded so a tight device skips it and falls back to the lazy standup on the first parse.
            if (hasKotlin) indexScope.launch {
                if (freeHeapBytes() >= PARSER_WARMUP_MIN_FREE_BYTES) {
                    runCatching { KotlinParserHost.warmUp() }
                    memLog.info("after Kotlin parser warm-up (parallel with index): ${MemSample.now().fmt()}")
                } else memLog.warn("skipped Kotlin parser warm-up (headroom ${MemSample.now().headroomMb}MB < ${PARSER_WARMUP_MIN_FREE_BYTES / MB_BYTES}MB floor)")
            }
            indexScope.launch {
                // Phase-0 build-process-isolation instrumentation (docs/build-process-isolation.md): track the
                // project-open memory storm (index + the retained Kotlin warm-ups) so its peak can be compared
                // against a build's peak — that comparison decides whether a separate build process targets the
                // dominant OOM. A periodic sampler catches the storm's true intra-phase peak (incl. the parser
                // warm-up now overlapping this build).
                val openPeak = PeakHeap().also { it.record() }
                val sampler = launch {
                    while (isActive) {
                        openPeak.record(); delay(MEM_SAMPLE_INTERVAL_MS.milliseconds)
                    }
                }
                try {
                    memLog.info("project open (before index): ${MemSample.now().fmt()}")
                    runCatching { indexService.ensureUpToDate(buildIndexScope()) }
                    memLog.info("after index build: ${MemSample.now().fmt()}")
                    // Compiler warm-up is the heap-heavy one and is only needed for the first Run/build (never for
                    // editing), so keep it AFTER the index build to bound the project-open peak.
                    if (hasKotlin) {
                        if (freeHeapBytes() >= COMPILER_WARMUP_MIN_FREE_BYTES) {
                            runCatching { kotlinJvmCompiler.warmUp(compileBootClasspath) }
                            memLog.info("after Kotlin compiler warm-up: ${MemSample.now().fmt()}")
                        } else memLog.warn("skipped Kotlin compiler warm-up (headroom ${MemSample.now().headroomMb}MB < ${COMPILER_WARMUP_MIN_FREE_BYTES / MB_BYTES}MB floor)")
                    }
                } finally {
                    sampler.cancel()
                    lastOpenPeak = openPeak.peak()
                    memLog.info("project-open peak: ${openPeak.peak().fmt()}")
                }
            }
        } else {
            indexScope.launch {
                runCatching { indexService.ensureUpToDate(buildIndexScope()) }; memLog.info(
                "after index build: ${MemSample.now().fmt()}"
            )
            }
            // Pre-warm the Kotlin parser environment (the ~200ms KotlinCoreEnvironment standup) off-thread, so
            // the first Kotlin completion/diagnostics/preview doesn't pay it on the interaction path. Gated on
            // the project actually containing Kotlin so a pure-Java project never stands up the Kotlin frontend.
            indexScope.launch { runCatching { if (projectHasKotlin()) KotlinParserHost.warmUp() } }
            // Also pre-warm the COMPILE path (the ~1s first-build cold start: class-loading the embeddable
            // compiler + standing up its environment). The parser-host warm-up above only loads the parse
            // classes; a throwaway compile loads the frontend + JVM backend, so the first real Run is warm.
            indexScope.launch {
                runCatching {
                    if (projectHasKotlin()) kotlinJvmCompiler.warmUp(
                        compileBootClasspath
                    )
                }
            }
        }
        // Arm the event hub's reactions only now: init's own commits (ensureKotlinStdlib) must not trigger
        // invalidation over fields that are still initializing (a throwing reaction would abort the commit).
        events.activate()
        // This is now the active project for app-level extension callbacks that fire outside any service scope
        // (the command actions, the synthetic-R provider, the XML resource host — all registered once on the
        // app registry). Set LAST in init, so the engine is fully constructed before it can be resolved as the
        // active one. The backend re-asserts this on project swap; an engine clears it (if still itself) on close.
        env.activeEngine = this
    }

    /** Heap a new allocation could still claim before hitting the cap (`max - used`) — the headroom before an
     *  OutOfMemoryError. Used to decide whether a retained Kotlin warm-up fits without tipping a tight device
     *  toward the low-memory killer. A conservative proxy: the LMK trips on *system* memory, not our heap, but
     *  a smaller app heap also means less system pressure, and skipping a warm-up while our own heap is already
     *  tight is the safe call. */
    private fun freeHeapBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    /** Cheap check (bounded, short-circuiting) for any `.kt` under the project's source roots. */
    private fun projectHasKotlin(): Boolean = runCatching {
        modules().any { m ->
            sourceRoots(m).any { root ->
                Files.exists(root) && Files.walk(root)
                    .use { s -> s.anyMatch { it.fileName?.toString()?.endsWith(".kt") == true } }
            }
        }
    }.getOrDefault(false)

    /**
     * Register this engine's PER-ENGINE container services. The application-scoped K2 compiler registers
     * idempotently on the shared application container (the next project reuses the warm one); this engine's
     * [EngineContext] is published on its own workspace container so the app-global service factories
     * (registered once in [registerStaticPlugins]) resolve the per-project engine through the scope.
     */
    private fun registerScopedServices() {
        // The app-scoped K2 compiler gets the host's plugin loader: on device the injected D8/dex one (so a
        // runtime Kotlin compiler plugin loads on ART), else the desktop URLClassLoader default.
        store.appContainer.registerServiceIfAbsent(KOTLIN_JVM_COMPILER) {
            KotlinJvmCompiler(pluginLoader = kotlinPluginLoader ?: DefaultKotlinPluginLoader)
        }
        // Publish this engine's [EngineContext] on its OWN workspace container, so the app-global service
        // factories (registered once on the app registry) resolve the per-project engine through the scope
        // (MODULE → WORKSPACE) instead of closure-capturing it. Just returns the already-built field — never
        // forces any other service's construction.
        store.workspaceContainer.registerService(ENGINE_CONTEXT) { this@IdeServices.engineContext }
    }

    /** The MODULE-scoped analyzer service key for [language]. */
    private fun analyzerKeyFor(language: LanguageId): ServiceKey<SourceAnalyzer> = when (language) {
        KotlinLanguageBackend.LANGUAGE_ID -> ANALYZER_KOTLIN
        XmlLanguageBackend.LANGUAGE_ID -> ANALYZER_XML
        else -> ANALYZER_JAVA
    }

    private fun buildIndexScope(): IndexScope {
        val jdt = modules().map { analyzerFor(it) }.filterIsInstance<JdtSourceAnalyzer>()
        val libraryJars =
            (jdt.flatMap { it.classpathJarPaths } + listOfNotNull(BundledKotlinStdlib.jar())).distinct()
        return IndexScope(
            sourceRoots = jdt.flatMap { it.sourceRootPaths }.distinct(),
            // The bundled kotlin-stdlib is always in scope (even for an editor-only Kotlin project that never
            // declared the dependency) so the Kotlin backend's callable/type-shape indexes carry `println`/
            // `listOf`/`String.trim`/… — the backend is a pure index consumer with no live stdlib jar scan.
            libraryJars = libraryJars,
            // Bundled/SDK jars (android.jar + desugar stubs, the bundled kotlin-stdlib) are re-extracted from
            // app assets, so their on-disk mtime is not stable across launches and the default path+size+mtime
            // key re-indexes them every cold start (android.jar alone is ~90% of all index entries). Give them a
            // PATH-free `<name>-<size>` key so the segment is reused across launches (and later matches a prebuilt
            // segment shipped under the same id). Keyed by the same Path objects that appear in [libraryJars].
            stableJarIds = libraryJars.mapNotNull { p -> bundledStableJarId(p)?.let { p to it } }.toMap(),
            jdkHome = jdt.firstNotNullOfOrNull { it.jdkHome },
            // Attached library/SDK SOURCE archives (incl. the downloaded JDK src.zip, folded in by the JDT
            // branch of analyzerFor) → the source-doc index: real param names + javadoc/KDoc.
            sourceArchives = jdt.flatMap { it.librarySourceArchives }.distinct(),
            // Index res/ XML. The project's OWN + dependency-module res goes to the resident source side
            // (editable); immutable dependency/AAR res goes to disk segments ([libraryResourceRoots]) so a
            // Material/AndroidX resource set (a ~600 KB merged values.xml + hundreds of files) is parsed once
            // and read on demand rather than re-scanned into the heap every launch.
            resourceRoots = modules().flatMap { m ->
                if (m.facets.get(AndroidFacet.KEY) != null) runCatching {
                    AndroidResources.projectResourceDirs(
                        m, store.workspace
                    )
                }.getOrDefault(emptyList())
                else resourceRoots(m)
            }.distinct(),
            libraryResourceRoots = modules().flatMap { m ->
                if (m.facets.get(AndroidFacet.KEY) != null) runCatching {
                    AndroidResources.libraryResourceDirs(
                        m, store.workspace
                    )
                }.getOrDefault(emptyList())
                else emptyList()
            }.distinct(),
        )
    }

    // Serializes the background ensureUpToDate passes: the event hub triggers a re-sync per model commit,
    // so back-to-back triggers (a reconcile loop's per-module commits) must queue, not interleave.
    private val indexSyncMutex = Mutex()

    /** Re-invalidate and rebuild the workspace indexes from scratch (the UI's "Re-index" action). */
    fun reindex() {
        invalidateSyntheticClasses()
        indexScope.launch {
            indexSyncMutex.withLock {
                runCatching {
                    indexService.invalidate()
                    indexService.ensureUpToDate(buildIndexScope())
                }
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
        indexScope.launch {
            indexSyncMutex.withLock { runCatching { indexService.ensureUpToDate(buildIndexScope()) } }
        }
    }

    // ---- SDK / toolchain manager (download Android SDK packages + JDK sources) ----

    /** Downloads Android SDK packages + JDK sources behind a progress flow. APPLICATION-scoped: one shared
     *  queue across every project (and the picker's hub), resolved from the shared application container —
     *  [ProjectManager] registers it; this engine registers it idempotently for the manager-less (test) path.
     *  This engine subscribes [sdkChangeSubscription] so a finished install re-attaches the new sources here. */
    val sdkManager: SdkManagerService
        get() = store.appContainer.let { c ->
            c.registerServiceIfAbsent(APP_SDK_MANAGER) { SdkManagerService(store.rootPath, sharedRoot = sharedCachesRoot) }
            c.getService(APP_SDK_MANAGER)
        }

    /** This engine's subscription to [sdkManager]'s change notifications (invalidate analyzers + reindex on a
     *  finished install). Held so [close] can unsubscribe it from the shared, longer-lived service. A build-only
     *  engine has no editor analyzers to refresh, so it doesn't subscribe (and doesn't build the SDK manager).
     *  Initialized as a property (not in [registerScopedServices]) so it runs after the engine's other fields. */
    private val sdkChangeSubscription: Disposable? =
        if (buildOnly) null else sdkManager.addChangeListener { events.librariesChanged() }

    /**
     * The compile bootclasspath. On-device ([androidTools] non-null) ecj has no readable JRE, so every compile
     * (and the analyzer + Kotlin warm-up) must be handed the bundled `android.jar` as the platform library; on
     * the desktop it is empty (the host JRE). Shared infrastructure: the build, the per-module analyzer, and
     * the Kotlin compiler warm-up all read it, so it stays engine-owned and is reached via [EngineContext].
     */
    val compileBootClasspath: List<Path> =
        listOfNotNull(androidTools?.androidJar) + (androidTools?.desugarStubs ?: emptyList())

    /**
     * The compile boot classpath for a SINGLE module — its resolved platform SDK ([SdkResolution]) rather
     * than the one workspace-global [compileBootClasspath]. A `java-*`/`kotlin-*` module resolves the
     * core-Java platform (so `android.*` never reaches its compile/analysis), an `android-*` module the
     * Android SDK. Only existing **jar files** are kept: a modular JDK home (the desktop core-Java SDK's
     * boot entry) is a directory the batch compiler can't take as `-bootclasspath`, so it drops to empty and
     * the build falls back to the host JRE — byte-for-byte the pre-change desktop behavior.
     */
    fun bootClasspathFor(module: Module): List<Path> =
        (SdkResolution.sdkFor(store.workspace, module)?.bootClasspath ?: emptyList())
            .map { Paths.get(it.path) }
            .filter { Files.isRegularFile(it) }

    /**
     * Provision the bundled kotlin-stdlib as a real project dependency for every module with `.kt` sources
     * (never the host runtime). Project setup, not build execution: run at open so completion/index see the
     * stdlib, and again by a build before it compiles a newly-added Kotlin module. Reached by the build via
     * [EngineContext.ensureKotlinStdlib].
     */
    fun ensureKotlinStdlib() {
        val libName = "kotlin-stdlib"
        val kotlinModules = store.workspace.projects.flatMap { p -> p.modules.map { p to it } }
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
                missing.forEach {
                    module(it.id).addDependency(
                        LibraryDependency(LibraryRef(libName), DependencyScope.IMPLEMENTATION)
                    )
                }
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
            .any { root ->
                Files.isDirectory(root) && runCatching {
                    Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".kt") } }
                }.getOrDefault(false)
            }

    /**
     * The Kotlin compiler: K2 in-process (`:lang-kotlin`'s [KotlinJvmCompiler]). APPLICATION-scoped — one
     * warm compiler shared across every opened project (its environment/jar filesystem is reused
     * build-to-build; the boot classpath is passed per compile, so one instance serves all projects).
     * Registered idempotently on the shared application container in init.
     */
    private val kotlinJvmCompiler: KotlinJvmCompiler
        get() = store.appContainer.getService(
            KOTLIN_JVM_COMPILER
        )

    /**
     * The app-global signing-keystore registry (create/import/validate/assign): keystores + their secrets
     * live under the shared home dir, shared across projects and kept OUT of any project. APPLICATION-scoped
     * and reached from the shared application container, so it's a single registry across every project and
     * the picker's hub can drive it with no project open ([ProjectManager] registers it; this engine
     * registers it idempotently for the manager-less test path). Falls back to the project's `.platform`
     * when the host supplies no shared dir (tests).
     */
    val keystoreRegistry: KeystoreRegistry
        get() = store.appContainer.let { c ->
            c.registerServiceIfAbsent(APP_KEYSTORE_REGISTRY) {
                KeystoreRegistry((sharedCachesRoot ?: store.rootPath.resolve(".platform")).resolve("keystores"))
            }
            c.getService(APP_KEYSTORE_REGISTRY)
        }

    private fun projectOf(module: Module): Project? =
        store.workspace.projects.firstOrNull { p -> p.modules.any { it.id == module.id } }

    /** [module] plus its transitive module-dependency closure (within its project) — the modules a build of
     *  [module] actually compiles/links against, so a dep module's unresolved library blocks it too. */
    private fun moduleBuildClosure(module: Module): List<Module> {
        val project = projectOf(module) ?: return listOf(module)
        val byId = project.modules.associateBy { it.id }
        val seen = LinkedHashSet<ModuleId>()
        val stack = ArrayDeque<Module>().apply { add(module) }
        while (stack.isNotEmpty()) {
            val m = stack.removeLast()
            if (!seen.add(m.id)) continue
            for (dep in m.dependencies.filterIsInstance<ModuleDependency>()) byId[dep.target]?.let {
                stack.add(
                    it
                )
            }
        }
        return seen.mapNotNull { byId[it] }
    }
    // Live editor buffers (absolute path -> text). Surfaced to the JDT analyzer as an in-memory overlay
    // (FQCN -> source) so completion resolves in-progress edits to dependency files without ever
    // touching disk — true working copies, not flushed buffers.
    private val openDocuments = ConcurrentHashMap<Path, String>()

    /** Called by the editor on every change so cross-file analysis sees the latest text. */
    fun updateDocument(file: Path, text: String) {
        openDocuments[file.toAbsolutePath().normalize()] = text
    }

    /** Persist a single editor buffer to disk and keep it as the live overlay (it now equals disk). The
     *  invalidation chain (JDT binding-cache drop, res→R refresh, xml re-index, kt→facade refresh) runs as
     *  the [events] hub's [FileChanged][dev.ide.vfs.FileChanged] reaction, not inline here. */
    fun save(file: Path, text: String) {
        val path = file.toAbsolutePath().normalize()
        // A binary/oversized file opened in the editor is shown as a read-only placeholder (FileBackend
        // .readFile), so `text` here is that placeholder, not the file's real content. Writing it would
        // destroy the asset (an opened PNG becomes ~150 bytes of text). Leave such a file untouched.
        if (isBinaryOnDisk(path)) return
        openDocuments[path] = text
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(text)
        events.fileChanged(path, text)
    }

    /** A path under an Android `res/` tree — a change to it can change the synthetic R class. */
    private fun isResourcePath(path: Path): Boolean =
        path.toString().replace('\\', '/').contains("/res/")

    /**
     * Write live editor buffers to disk ("save all"). Completion runs off the in-memory overlay, but the
     * compiler reads sources from disk, so a build must flush first, or it compiles stale files. Only
     * writes when content actually changed. Public so the UI process can flush before a build runs in the
     * separate `:build` daemon — the daemon has no editor buffers of its own, so its own flush is a no-op and
     * it would otherwise compile whatever was last saved to disk (running stale code on an unsaved edit).
     */
    fun flushOpenDocuments() {
        for ((path, content) in openDocuments) {
            runCatching {
                // Same guard as save(): an opened binary/oversized file lives in the overlay as placeholder
                // text (its bytes never differ from that placeholder), so flushing it before a build would
                // overwrite the real bytes on disk. Skip it — this is the frequent PNG-corruption path.
                if (isBinaryOnDisk(path)) return@runCatching
                if (!Files.exists(path) || path.readText() != content) {
                    path.parent?.let { Files.createDirectories(it) }
                    path.writeText(content)
                }
            }
        }
    }

    /**
     * True if [path] currently exists on disk as content the editor treats as non-text — a binary file (a
     * NUL byte within the first block) or one larger than [MAX_EDITOR_TEXT_BYTES]. This is the write-side
     * counterpart to [dev.ide.core.backend.FileBackend.readFile]'s binary sniff: a file that read-side
     * refuses to load as text must never be written back from an editor overlay. Errors sniff as "not
     * binary" so a genuine text file is never silently skipped.
     */
    private fun isBinaryOnDisk(path: Path): Boolean = runCatching {
        if (!Files.isRegularFile(path)) return@runCatching false
        if (Files.size(path) > MAX_EDITOR_TEXT_BYTES) return@runCatching true
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(8000)
            val n = ins.read(buf)
            n > 0 && (0 until n).any { buf[it].toInt() == 0 }
        }
    }.getOrDefault(false)

    // Synthetic ("light") classes (e.g. Android R) contributed via `platform.syntheticClass`, rendered to
    // Java source and merged into the overlay so they resolve for completion AND analysis like real types.
    // Cached (the scan walks resource dirs); invalidated when resources change (save/createFile/reindex).
    @Volatile
    private var syntheticCache: Map<String, CharArray>? = null

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
                for (sc in classes) runCatching {
                    out[sc.fqName] = SyntheticJavaSource.emit(sc).toCharArray()
                }
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
                .filterNot { it is KotlinSyntheticClassProvider }
                .flatMap { runCatching { it.classesFor(ctx) }.getOrDefault(emptyList()) }
        }

    /** Drop the cached synthetic classes so the next analyze/complete regenerates them (e.g. after a res edit). */
    fun invalidateSyntheticClasses() {
        // Drop the rendered synthetic R/facade caches. The shared resource repository ([repoCache]) is NOT
        // dropped here: it is fingerprint-keyed on the res files, so a resource edit invalidates it on its own,
        // while an unrelated .kt edit (which also calls this) must not throw away the parsed resource set.
        syntheticCache = null; kotlinSyntheticCache.clear()
    }

    /** The open buffers as an FQCN -> source overlay for the name environment, plus synthetic light classes. */
    private fun overlay(): Map<String, CharArray> {
        val synthetic = syntheticOverlay()
        val map = HashMap<String, CharArray>(synthetic.size + openDocuments.size)
        map.putAll(synthetic)
        for ((path, content) in openDocuments) {
            val fqcn = fqcnOf(path, content) ?: continue
            map[fqcn] =
                content.toCharArray() // a real, open file overrides a synthetic of the same FQCN
        }
        return map
    }

    /**
     * The open editor buffers for Kotlin source as a path → text overlay (keys match `VirtualFile.path`, i.e.
     * the absolute normalized path), for the Kotlin symbol model's cross-file freshness. A fresh snapshot per
     * call (NOT the live `openDocuments` map) so the model's content-diff in `setOverlay` works — handing back
     * the same mutable instance would always compare equal and never invalidate.
     */
    private fun kotlinOverlay(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((path, content) in openDocuments) {
            val s = path.toString()
            if (s.endsWith(".kt") || s.endsWith(".kts")) out[s] = content
        }
        return out
    }

    /** The IDE's stable, path-free index-segment key for a BUNDLED jar (android.jar / desugar stubs / the
     *  bundled kotlin-stdlib; re-extracted from assets, so mtime is unstable), or null for a normal jar. */
    private fun bundledStableJarId(p: Path): String? {
        val norm = p.toAbsolutePath().normalize().toString()
        val bundled = (compileBootClasspath + listOfNotNull(BundledKotlinStdlib.jar()))
            .map { it.toAbsolutePath().normalize().toString() }
        if (norm !in bundled) return null
        return "${p.fileName}-${runCatching { Files.size(p) }.getOrDefault(0L)}"
    }

    private fun fqcnOf(path: Path, text: String): String? {
        val cls =
            path.fileName.toString().removeSuffix(".java").takeIf { it.isNotEmpty() } ?: return null
        val pkg = PACKAGE_DECL.find(text)?.groupValues?.get(1)
        return if (pkg.isNullOrEmpty()) cls else "$pkg.$cls"
    }

    val workspaceRoot: Path get() = store.rootPath

    /** The model's current revision (bumped on every model commit). A separate-process build runner passes
     *  this to the daemon so it reloads `module.toml` from disk when the model changed since it last opened
     *  — without it, a build in `:build` keeps using the config frozen at the daemon's first open. */
    val modelGeneration: Int get() = store.generation

    // ---- settings (the Settings screen) -----------------------------------------------------------

    /** User-tunable completion knobs (max items + optional contributors); applied to the next [complete]. */
    @Volatile
    var completionOptions: CompletionOptions =
        CompletionOptions()

    /** Settings pages contributed by plugins (the built-in pages are assembled by the backend). */
    fun settingsPages(): List<SettingsPage> =
        platform.extensions.extensions(SETTINGS_PAGE_EP)

    /** Every registered analyzer — the inspection catalogue for the Analysis settings list. */
    fun registeredAnalyzers(): List<Analyzer> =
        platform.extensions.extensions(ANALYZER_EP)

    @Volatile
    private var inspectionProfileState: AnalysisProfile = loadInspectionProfile()

    /** The active inspection profile (enable/disable + severity overrides), persisted per project. */
    fun inspectionProfile(): AnalysisProfile = inspectionProfileState

    /**
     * Enable/disable inspection [id] and override its severity ([severity] null = no override, use the
     * analyzer default). Persists to `.platform/inspections.properties` and re-configures the live engine
     * (which re-publishes diagnostics for open files).
     */
    fun setInspection(
        id: AnalyzerId, enabled: Boolean, severity: Severity?
    ) {
        val cur = inspectionProfileState
        val disabled = cur.disabled.toMutableSet().apply { if (enabled) remove(id) else add(id) }
        val overrides = cur.severityOverrides.toMutableMap()
            .apply { if (severity != null) put(id, severity) else remove(id) }
        val next = AnalysisProfile(disabled, overrides)
        inspectionProfileState = next
        persistInspectionProfile(next)
        analysisEngine.configure(next)
    }

    private val projectSettingsFile: Path get() = store.rootPath.resolve(".platform/settings.properties")
    private val inspectionsFile: Path get() = store.rootPath.resolve(".platform/inspections.properties")

    /** Read a per-project generic-settings value (the store behind PROJECT-scoped settings pages). */
    fun projectPref(key: String): String? = loadProps(projectSettingsFile).getProperty(key)

    /** Write a per-project generic-settings value. */
    fun setProjectPref(key: String, value: String) {
        val props = loadProps(projectSettingsFile).apply { setProperty(key, value) }
        storeProps(projectSettingsFile, props, "CodeAssist project settings")
    }

    // --- active build variant (per module) -----------------------------------------------------
    // The build variant the editor analyzes against and the build/run default targets. Persisted per
    // module in .platform/settings.properties; defaults to the module's default (debug-ish) variant.

    private fun variantPrefKey(module: Module) = "variant.${module.name}"

    /** The active build-variant name for [module] (its persisted choice, else the default variant, else "main"). */
    fun activeVariant(module: Module): String =
        projectPref(variantPrefKey(module))
            ?: AndroidVariants.defaultVariant(module)?.name
            ?: "main"

    /** All selectable build-variant names for [module] (empty for a non-Android module). */
    fun listVariants(module: Module): List<String> =
        AndroidVariants.compute(module).map { it.name }

    /** Select [variantName] as [module]'s active variant; re-analyzes + re-indexes if it changed (the hub's
     *  `variant.*` settings reaction; a variant switch changes the variant-filtered classpath). */
    fun setActiveVariant(module: Module, variantName: String) {
        if (activeVariant(module) == variantName) return
        setProjectPref(variantPrefKey(module), variantName)
        events.settingChanged(
            BuiltInSettingsPages.BUILD, variantPrefKey(module), projectScoped = true,
        )
    }

    /** [listVariants] by module name (the string-keyed backend surface). */
    fun listVariants(moduleName: String): List<String> =
        modules().firstOrNull { it.name == moduleName }?.let { listVariants(it) } ?: emptyList()

    /** The active variant for [moduleName], or null when the module is unknown or has no variants (non-Android). */
    fun activeVariant(moduleName: String): String? {
        val m = modules().firstOrNull { it.name == moduleName } ?: return null
        if (listVariants(m).isEmpty()) return null
        return activeVariant(m)
    }

    /** [setActiveVariant] by module name (no-op for an unknown module). */
    fun setActiveVariant(moduleName: String, variantName: String) {
        modules().firstOrNull { it.name == moduleName }?.let { setActiveVariant(it, variantName) }
    }

    /** The active variant's dependency config-name set for [module] (null = non-Android → no variant filter). */
    private fun activeConfigs(module: Module): Set<String>? =
        AndroidVariants.select(module, activeVariant(module))?.configurations

    private fun persistInspectionProfile(profile: AnalysisProfile) {
        // "<id>=off" disables; "<id>=<SEVERITY>" overrides; an enabled-default analyzer is simply omitted.
        val props = java.util.Properties()
        for (id in profile.disabled) props.setProperty(id.value, "off")
        for ((id, sev) in profile.severityOverrides) if (id !in profile.disabled) props.setProperty(
            id.value, sev.name
        )
        storeProps(inspectionsFile, props, "CodeAssist inspection profile")
    }

    private fun loadInspectionProfile(): AnalysisProfile {
        val props = loadProps(inspectionsFile)
        if (props.isEmpty) return AnalysisProfile.DEFAULT
        val disabled = mutableSetOf<AnalyzerId>()
        val overrides = mutableMapOf<AnalyzerId, Severity>()
        for (name in props.stringPropertyNames()) {
            val v = props.getProperty(name)
            if (v.equals("off", ignoreCase = true)) disabled.add(AnalyzerId(name))
            else runCatching { Severity.valueOf(v) }.getOrNull()
                ?.let { overrides[AnalyzerId(name)] = it }
        }
        return AnalysisProfile(disabled, overrides)
    }

    private fun loadProps(file: Path): java.util.Properties = java.util.Properties().apply {
        if (Files.exists(file)) Files.newInputStream(file).use { load(it) }
    }

    private fun storeProps(file: Path, props: java.util.Properties, comment: String) {
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { props.store(it, comment) }
    }

    /**
     * Delete the project's regenerable caches under `.platform/caches` (language extension, custom-view,
     * preview, and build caches) — never source, settings, or the shared cross-project index store. Returns
     * a short human summary of the space freed.
     */
    fun clearProjectCaches(): String {
        val cachesRoot = store.rootPath.resolve(".platform/caches")
        var freed = 0L
        var removed = 0
        for (name in CLEARABLE_CACHE_DIRS) {
            val dir = cachesRoot.resolve(name)
            if (!Files.isDirectory(dir)) continue
            runCatching {
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .forEach { freed += runCatching { Files.size(it) }.getOrDefault(0L) }
                }
                Files.walk(dir).use { s ->
                    s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
                removed++
            }
        }
        return if (removed == 0) "Caches already clear" else "Freed ${humanBytes(freed)}"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    fun modules(): List<Module> = store.workspace.projects.flatMap { it.modules }

    /** Module display names — so a host can log/show them without depending on the model API. */
    fun moduleNames(): List<String> = modules().map { it.name }

    /** The project templates registered into this workspace's platform (the Create-Project gallery). */
    fun projectTemplates(): List<ProjectTemplate> =
        ProjectTemplateRegistry(platform.extensions).all()

    /** The model project's display name (e.g. "MyApp"), falling back to the workspace dir name. */
    fun projectDisplayName(): String =
        store.workspace.projects.firstOrNull()?.name ?: (workspaceRoot.fileName?.toString()
            ?: "workspace")

    /** True when this project was imported from Gradle and runs in compatibility mode. */
    fun isCompatibilityMode(): Boolean = GradleImport.isCompatibilityMode(workspaceRoot)

    /** The reader notes recorded at import/sync time (what the tolerant Gradle reader couldn't fully extract). */
    fun compatibilityNotes(): List<String> = GradleImport.readNotes(workspaceRoot)

    /**
     * Re-read the Gradle build scripts still present at [workspaceRoot] into the OPEN model: add any new
     * modules, and refresh each module's declared dependencies + Android facet from the scripts (the scripts
     * are the source of truth in compatibility mode, so a user-added dependency not in the scripts is
     * dropped). Model + persistence only — the caller re-resolves dependencies and re-indexes afterwards.
     */
    internal fun syncGradleFromScripts(): GradleSyncOutcome {
        val spec = GradleImport.parse(workspaceRoot)
            ?: return GradleSyncOutcome(false, "No Gradle build scripts were found to sync from.", emptyList())
        val level = store.workspace.projects.firstOrNull()?.modules?.firstOrNull()?.languageLevel ?: LanguageLevel.JAVA_17
        val (added, updated) = GradleImport.reconcile(store, spec, level)
        store.save()
        GradleImport.markCompatibilityMode(workspaceRoot, spec.report.notes)
        val message = buildString {
            append("Synced from Gradle")
            if (added > 0) append(" · $added module${if (added == 1) "" else "s"} added")
            if (updated > 0) append(" · $updated module${if (updated == 1) "" else "s"} updated")
        }
        return GradleSyncOutcome(true, message, spec.report.notes)
    }

    fun sourceRoots(module: Module): List<Path> = module.sourceSets.flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
        .map { Paths.get(it.dir.path) }

    /** Content roots to surface in the project tree — code, resources, and assets (so `res/` is editable). */
    fun treeRoots(module: Module): List<Path> {
        return module.sourceSets.flatMap { it.contentRoots }
            .filter { cr -> cr.roles.any { it in TREE_ROOT_ROLES } }.map { Paths.get(it.dir.path) }
    }

    /**
     * The same surfaced roots as [treeRoots], but keeping each root's source-set name and [ContentRole]s
     * so the tree can pick a distinct icon (Java sources vs `res/` vs `assets/`) and know which roots are
     * package contexts (compactable + new-class targets).
     */
    fun treeRootsDetailed(module: Module): List<TreeRootInfo> = module.sourceSets.flatMap { ss ->
        ss.contentRoots.filter { cr -> cr.roles.any { it in TREE_ROOT_ROLES } }
            .map { TreeRootInfo(Paths.get(it.dir.path), ss.name, it.roles) }
    }

    /**
     * The module's `AndroidManifest.xml` (from its [AndroidFacet]), or null for a non-Android module. It
     * sits above the source roots, so the tree surfaces it explicitly. Resolved relative to the module dir
     * (derived from the output dir's `build/classes` convention).
     */
    fun manifestPath(module: Module): Path? {
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val moduleDir = Paths.get(module.outputDir.path).parent?.parent ?: return null
        return moduleDir.resolve(facet.manifest)
    }

    /**
     * The module's root directory on disk — where its `module.toml` lives — derived from the output-dir
     * `<moduleRoot>/build/classes` convention (same basis as [manifestPath]). Null if it can't be resolved.
     */
    fun moduleRoot(module: Module): Path? = Paths.get(module.outputDir.path).parent?.parent

    /**
     * The dotted package a [dir] corresponds to — its path relative to the enclosing source root — or null
     * when [dir] isn't under any module's source root. Returns "" for a source root itself (default package).
     * Used to scaffold a `package` line when a new `.java`/`.kt` file is created in the tree.
     */
    fun packageOf(dir: Path): String? {
        val d = dir.toAbsolutePath().normalize()
        for (m in modules()) for (root in sourceRoots(m)) {
            val r = root.toAbsolutePath().normalize()
            if (d == r) return ""
            if (d.startsWith(r)) return r.relativize(d).toString().replace('/', '.')
                .replace('\\', '.').trim('.')
        }
        return null
    }

    /** The module that owns [file] (its source root is a prefix), or null if outside the project. */
    fun moduleForFile(file: Path): Module? {
        val target = file.toAbsolutePath().normalize()
        return modules().firstOrNull { module -> sourceRoots(module).any { target.startsWith(it) } }
    }

    /** The per-(module, language) analyzer, resolved (and cached) as a MODULE-scoped service. */
    private fun analyzerFor(
        module: Module, language: LanguageId = LanguageId("java")
    ): SourceAnalyzer = module.service(analyzerKeyFor(language))

    /** Construct the analyzer for [module] in [language]. Invoked once by the module-scoped analyzer
     *  service factory; the module container caches and disposes the result. */
    private fun buildAnalyzer(module: Module, language: LanguageId): SourceAnalyzer =
        backendFor(language).createAnalyzer(
            ModuleCompilationContext.create(
                store.workspace, module, activeConfigs(module)
            )
        ).also {
            when (it) {
                is JdtSourceAnalyzer -> {
                    it.overlayProvider = ::overlay
                    it.indexService = indexService
                    // A downloaded JDK's src.zip (if any) → JDK names/javadoc for the editor.
                    sdkManager.jdkSourceOverride()?.let { zip -> it.addSourceJars(listOf(zip)) }
                    // SDK-Manager-installed Android framework sources → android.* param names + javadoc. On
                    // device the bundled android.jar is a flat asset, so the analyzer can't derive the sources
                    // dir from its path; attach it explicitly from the SDK-Manager install root (matched to the
                    // module's compileSdk). Deduped, so it's a no-op on desktop where `configure` already found it.
                    module.facets.get(AndroidFacet.KEY)?.let { facet ->
                        sdkManager.androidSourcesDir(facet.compileSdk)?.let { dir -> it.addSourceDirs(listOf(dir)) }
                    }
                }

                is KotlinSourceAnalyzer -> {
                    // Java/Android interop via the shared `java.classNames` index (type-name completion);
                    // the classpath extension scan persists alongside the index caches.
                    // EXCEPTION — the hidden Learn Compose scratch (an `android-library` under `.scratch/`): its
                    // library composables (`Text`/`Column`/…) must resolve from the ClasspathReader scan, NOT the
                    // shared `KotlinCallableIndex`. That index reuses content-addressed segments that don't carry
                    // this throwaway module's AAR top-level callables, so `Text` resolved to 0 candidates and the
                    // `@Preview` never rendered. index=null routes `topLevelByName` through the (proven) scan.
                    val isScratch = store.rootPath.toString().replace('\\', '/').contains("/.scratch/")
                    val scratchCompose = isScratch &&
                        module.facets.get(AndroidFacet.KEY) != null
                    it.indexService = if (scratchCompose) null else indexService
                    it.extensionCacheDir = store.rootPath.resolve(".platform/caches/kotlin-ext")
                    // Synthetic "light" classes (Android R/BuildConfig, …), minus the Kotlin file facades.
                    it.syntheticClassProvider = { kotlinSyntheticClasses(module) }
                    // Real parameter names + javadoc/KDoc from attached sources: the persistent source-doc
                    // index, with the module's JDT resolver (project dirs + -sources.jars + JDK src.zip +
                    // Android sources) as the live parse fallback before the index has built.
                    val jdtFallback =
                        (analyzerFor(module) as? JdtSourceAnalyzer)?.sourceMethodResolver
                            ?: SourceDocProvider.NONE
                    it.sourceDocProvider = IndexBackedSourceDocs(indexService, jdtFallback)
                    // Live editor buffers (path → text) so cross-file completion/resolution/diagnostics see
                    // unsaved edits in OTHER open .kt files before they're saved + reindexed.
                    it.liveOverlayProvider = ::kotlinOverlay
                }

                is XmlSourceAnalyzer -> {
                    // Inject the Android knowledge: layout metadata (SDK attrs.xml asset when present, else
                    // the curated catalog) + custom-view attributes (project/AAR attrs.xml, parsed once per
                    // analyzer) + resource candidates (name + value hint, straight from the incremental index)
                    // + framework `@android:` resource names (android.jar's android.R, scanned + cached).
                    // One bytecode scan feeds both: the custom-view tags AND the ancestry that lets a custom/
                    // library view inherit its base classes' app: attributes (+ AppCompat substitutions).
                    val scan = runCatching { customViewScan(module) }.getOrNull()
                    val srcScan = runCatching { sourceCustomViewScan() }.getOrNull()
                    // Library/AAR custom views (compiled) PLUS the project's own source View subclasses, and the
                    // merged ancestry (simple → super simple) that lets each inherit its framework superclass's
                    // android: attributes (android:text from Button, …), exactly as Android Studio does.
                    val customSupers = (scan?.superNames ?: emptyMap()) + (srcScan?.superNames ?: emptyMap())
                    val custom = runCatching { customAttrsMetadata(module, customSupers) }.getOrNull()
                    val customViews = ((scan?.widgets ?: emptyList()) + (srcScan?.widgets ?: emptyList()))
                        .distinctBy { it.tag }
                    val androidXml = AndroidXmlContributor(
                        resources = { type -> resourceCandidatesFor(module, type) },
                        // Augment the framework hierarchy with the custom/library/source view ancestry, so a
                        // custom view (MaterialButton → AppCompatButton → Button, or a project MyButton → Button)
                        // inherits its framework superclass's android: attributes, exactly as Android Studio does.
                        layout = { sdkLayoutMetadata().withCustomHierarchy(customSupers) },
                        customAttrs = { custom },
                        customViews = { customViews },
                        frameworkResources = { type -> frameworkResources(type) },
                    )
                    it.contributors = listOf(androidXml)
                    // Parameter hints: when the caret is inside `="…"`, describe the attribute's accepted values
                    // (enum/flags/boolean/@refs) from the same Android schema completion uses.
                    it.valueHintProvider = { pos -> androidXml.describeValue(pos) }
                    // Inlay hints: resolve a local `@type/name` to its value straight from the resource index
                    // (the value field carried on each declaration); no repository, no per-keystroke parse.
                    it.inlayResourceResolver =
                        XmlResourceValueResolver { rClass, name ->
                            ResourceType.byRClass(rClass)?.let { resourceHintValue(it, name) }
                        }
                }
            }
            // NB: the module container owns disposal. An evicted/removed module's container disposes
            // its analyzer promptly so the cached library-jar handles (the Kotlin ClasspathReader's
            // ZipFiles, the JDT env cache) are released right away, not deferred to workspace close —
            // which on ART had surfaced as a flood of "A ZipFile failed to close" CloseGuard warnings.
        }

    /**
     * Drop every cached per-module analyzer. A dependency change on one module affects the compile
     * classpath of every module that depends on it (an exported (`api`) library flows downstream), so a
     * per-module eviction would leave dependents analyzing against a stale classpath. Disposing all module
     * containers releases every analyzer's library-jar handles immediately and rebuilds them lazily (and
     * against the current snapshot) on the next analyze/complete.
     */
    private fun invalidateAnalyzers() {
        store.disposeAllModuleContainers()
        // The preview caches' fingerprints track res/source FILES but not the dependency classpath, which
        // changes here (deps/SDK/facet/language-level edits) — so drop them to force a rebuild.
        repoCache.clear()
        customViewCache.clear()
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

    /**
     * Completion for [text] (the live editor buffer) at [offset], bound to [file]'s module + language. Runs
     * through the unified [CompletionEngine]: the language backend (wrapped as a contributor), every
     * cross-cutting / plugin [CompletionContributor], and buffer-word completion are
     * merged and ranked in one pipeline — there is no privileged backend call here anymore. `suspend`
     * because a contributor may genuinely leave the worker mid-run (out-of-process work off the engine worker).
     */
    suspend fun complete(
        file: Path,
        text: String,
        offset: Int,
    ): CompletionResult {
        val lang = languageFor(file)
        val safeOffset = offset.coerceIn(0, text.length)
        val prefix = identifierPrefixBefore(text, safeOffset)
        val replaceRange = TextRange(safeOffset - prefix.length, safeOffset)
        val module = moduleForEditableFile(file)
        val analyzer = module?.let { analyzerFor(it, lang) }
        if (module != null) updateDocument(
            file, text
        ) // the live buffer joins the overlay the analyzer reads
        val snapshot = EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text)
        // A backend that throws mid-completion (e.g. the Kotlin parse host on ART) would otherwise propagate
        // out to the UI and surface as a silent empty popup with no trace. Log the cause (logcat/stderr) and
        // degrade to no suggestions, so one failing file can't disable completion and the failure is diagnosable.
        // Preemption is NOT a failure: a superseded request surfaces as EngineCanceledException and must
        // reach the host (which keeps the current popup) rather than degrade to an empty list that clobbers it.
        return try {
            // Parse the LIVE snapshot (not the cached lastByFile tree, which can lag the just-typed buffer)
            // so `position`/`parsedFile` reflect the completion buffer — the receiver-type-driven postfix
            // contributor depends on it. parseFull reuses the cached parse when the text is unchanged.
            val parsed =
                analyzer?.let { runCatching { it.incrementalParser.parseFull(snapshot) }.getOrNull() }
            val params = CompletionParams(
                document = snapshot,
                offset = safeOffset,
                prefix = prefix,
                language = lang,
                trigger = CompletionTrigger.Explicit,
                replacementRange = replaceRange,
                position = parsed?.nodeAt(safeOffset),
                parsedFile = parsed,
                typeResolver = analyzer?.let { a -> { node -> runCatching { a.resolveType(node) }.getOrNull() } },
            )
            // Per-call contributions: the language backend's own completion contributors, merged with the
            // EP (cross-cutting / plugin) contributors by the engine.
            completionEngine.complete(params, analyzer?.completionContributions() ?: emptyList(), completionOptions)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: EngineCanceledException) {
            throw e
        } catch (e: Throwable) {
            System.err.println("[IdeServices] completion failed for $file (${lang.id}): ${e.stackTraceToString()}")
            CompletionResult(emptyList(), false, replaceRange)
        }
    }

    /** The identifier-like prefix immediately before [offset] (letters/digits/`_`/`$`). */
    private fun identifierPrefixBefore(text: String, offset: Int): String {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && isWordChar(text[i - 1])) i--
        return text.substring(i, offset.coerceIn(0, text.length))
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** Parameter-info / signature help at [offset] in [text] (the live buffer), bound to [file]'s module +
     *  language. Null when the file is outside the project, its backend has no signature-help service, or the
     *  caret isn't inside a resolvable call. */
    fun signatureHelp(
        file: Path, text: String, offset: Int
    ): dev.ide.lang.signature.SignatureHelp? {
        val module = moduleForEditableFile(file) ?: return null
        updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.signatureHelp ?: return null
        val snapshot = EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text)
        val request = dev.ide.lang.signature.SignatureHelpRequest(
            snapshot, offset, dev.ide.lang.signature.SignatureHelpTrigger.CursorUpdate,
        )
        return runCatching { runSync { service.signatureHelp(request) } }.getOrNull()
    }

    /** Inlay hints for [text] (the live buffer) in `[startOffset, endOffset)`, bound to [file]'s module +
     *  language. Empty when the file is outside the project or its backend has no inlay-hint service. */
    fun inlayHints(
        file: Path, text: String, startOffset: Int, endOffset: Int
    ): List<dev.ide.lang.hints.InlayHint> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.inlayHints ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        // Refresh the analyzer's parse of the live buffer (the XML hint service reads the last parse; the JDT/
        // Kotlin services read their overlay, for which this reparse is a cheap no-op when text is unchanged).
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return runCatching {
            runSync {
                service.hints(
                    vf, TextRange(startOffset, endOffset)
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Type-aware semantic-highlight tokens for [text], bound to [file]'s module (empty if outside the project
     *  or the backend has no highlighter). Runs on the engine thread, like analysis/hints. */
    fun semanticTokens(file: Path, text: String): List<dev.ide.lang.highlight.SemanticToken> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.semanticHighlighter ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        // Refresh the analyzer's parse of the live buffer (the Kotlin highlighter reads the last parse).
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return runCatching { runSync { service.highlight(vf) } }.getOrDefault(emptyList())
    }

    /** Foldable regions for [file]'s live buffer — imports, type/function bodies, block comments. */
    fun codeFolds(file: Path, text: String): List<dev.ide.lang.folding.FoldRegion> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = analyzerFor(module, languageFor(file))
        val service = analyzer.folding ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        // Refresh the analyzer's parse of the live buffer (the folder reads the last parse).
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return runCatching { runSync { service.folds(vf) } }.getOrDefault(emptyList())
    }

    /** Reformat the whole live buffer of [file] to [style]; minimal edits, or empty if the language has no
     *  formatter / the buffer is already formatted / can't be safely formatted. */
    fun formatDocument(file: Path, text: String, style: dev.ide.lang.formatting.FormatStyle): List<DocumentEdit> {
        val service = formattingServiceFor(file) ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        return runCatching { runSync { service.format(vf, text, style) } }.getOrDefault(emptyList())
    }

    /** Reformat only the text overlapping `[start, end)` of [file]'s live buffer. */
    fun formatRange(file: Path, text: String, start: Int, end: Int, style: dev.ide.lang.formatting.FormatStyle): List<DocumentEdit> {
        val service = formattingServiceFor(file) ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        val range = TextRange(start, end)
        return runCatching { runSync { service.formatRange(vf, text, range, style) } }.getOrDefault(emptyList())
    }

    private fun formattingServiceFor(file: Path): dev.ide.lang.formatting.FormattingService? {
        val module = moduleForEditableFile(file) ?: return null
        return analyzerFor(module, languageFor(file)).formatting
    }

    /** Format the built-in code sample for [languageId] with [style] and return the result — for the Code
     *  Style screen's live preview. Module-independent: it builds a standalone formatter, so it works with no
     *  file open. Returns the sample unchanged if formatting fails. */
    fun formatStylePreview(languageId: String, style: dev.ide.lang.formatting.FormatStyle): String {
        val sample = dev.ide.core.settings.CodeStyleSamples.forLanguage(languageId)
        val edits = runCatching {
            when (languageId) {
                dev.ide.core.settings.CodeStyleSettings.LANG_KOTLIN ->
                    dev.ide.lang.kotlin.KotlinFormatter.reformat("Preview.kt", sample, style, 0, sample.length)
                else ->
                    // A high default compliance so newer Java syntax in the sample parses; the preview is not
                    // tied to any module.
                    dev.ide.lang.jdt.formatting.JdtFormattingService("17").formatText(sample, style)
            }
        }.getOrDefault(emptyList())
        if (edits.isEmpty()) return sample
        val sb = StringBuilder(sample)
        for (e in edits.sortedByDescending { it.offset }) {
            val s = e.offset.coerceIn(0, sb.length)
            val en = (e.offset + e.oldLength).coerceIn(s, sb.length)
            sb.replace(s, en, e.newText.toString())
        }
        return sb.toString()
    }

    /** Enclosing declarations (type/method names, outer→inner) at [offset] in [file]'s buffer — for the
     *  cursor-tracking breadcrumb. Empty if the file is outside the project or not JDT-backed. */
    fun breadcrumbAt(file: Path, text: String, offset: Int): List<String> {
        val module = moduleForFile(file) ?: return emptyList()
        updateDocument(file, text)
        val analyzer = analyzerFor(module) as? JdtSourceAnalyzer ?: return emptyList()
        return runCatching {
            analyzer.enclosingStructure(
                store.vfs.fileFor(file), text, offset
            )
        }.getOrDefault(emptyList())
    }

    /** The file's declarations (for the structure/outline view + sticky scroll headers), via the language
     *  analyzer's structure walk. Empty if the file is outside the project or the backend doesn't support it. */
    fun fileStructure(file: Path, text: String): List<dev.ide.lang.resolve.StructureItem> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(module, languageFor(file))
        val vf = store.vfs.fileFor(file)
        return runCatching { analyzer.fileStructure(vf, text) }.getOrDefault(emptyList())
    }

    /** Quick documentation (signature + doc comment) for the symbol at [offset] in [file]'s buffer, or null.
     *  Resolves through the live overlay so cross-file references reach their declaration. */
    fun quickDocAt(file: Path, text: String, offset: Int): dev.ide.lang.resolve.QuickDocInfo? {
        val module = moduleForEditableFile(file) ?: return null
        updateDocument(file, text)
        val analyzer = analyzerFor(module, languageFor(file))
        val vf = store.vfs.fileFor(file)
        return runCatching { runSync { analyzer.quickDoc(vf, text, offset) } }.getOrNull()
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

    /**
     * Parse [file]'s live buffer [text] into the neutral tolerant DOM — the same one the analyzer/completion
     * use — via the module's incremental parser. Null if [file] is outside the project. The shared parse
     * primitive ([EngineContext.parse]); the block projection service rides on it.
     */
    private fun parse(file: Path, text: String): ParsedFile? {
        val module = moduleForFile(file) ?: return null
        val analyzer = analyzerFor(module, languageFor(file))
        return analyzer.incrementalParser.parseFull(
            EditorDocument(
                store.vfs.fileFor(file), docVersion.incrementAndGet(), text
            )
        )
    }

    // ---- analysis (diagnostics) ----

    private val analysisEnvironment = IdeAnalysisEnvironment()

    /**
     * The diagnostics engine: one multi-language pipeline. Each language module contributes its own
     * analyzers / diagnostic providers / quick-fix + action providers onto the `platform.*` extension
     * points through its `*AnalysisSupport.register` hook (mirroring `AndroidSupport.register`); the engine
     * is assembled by *querying* the registry and dispatches each provider by the file's language (see the
     * `languages` filters in [dev.ide.analysis.impl.AnalysisEngine]). So adding a language's editor features
     * is a registration, not a host edit — and the host no longer contains any per-language analysis logic.
     */
    /** This engine's XML resource host (the per-project resource/index lookups the XML diagnostics need). The
     *  app-global XML analysis support delegates to the active engine's instance (see [registerActiveEnginePlugins]). */
    internal val xmlResourceHost: dev.ide.lang.xml.lint.XmlResourceHost by lazy { IdeXmlResourceHost() }

    private val analysisEngine = run {
        // Every analyzer / diagnostic / quick-fix / action provider is now contributed app-global (the stateless
        // JDT + Kotlin support, and the XML support via the active engine — see [registerStaticPlugins]); this
        // just QUERIES the (hierarchical) registry, so it sees both app-global and any project-local providers.
        AnalysisEngine(
            analyzers = platform.extensions.extensions(ANALYZER_EP),
            quickFixProviders = platform.extensions.extensions(QUICK_FIX_PROVIDER_EP),
            diagnosticProviders = platform.extensions.extensions(DIAGNOSTIC_PROVIDER_EP),
            environment = analysisEnvironment,
            scope = indexScope,
            actionProviders = platform.extensions.extensions(ACTION_PROVIDER_EP),
        ).also { engine ->
            // Apply the persisted per-project inspection profile (disabled checks + severity overrides).
            if (inspectionProfileState != AnalysisProfile.DEFAULT) engine.configure(
                inspectionProfileState
            )
        }
    }

    // The analysis pipeline parses via JDT's DOM ASTParser, which on ART can reference JDK-9+ platform
    // classes absent from the runtime (e.g. java.lang.Runtime$Version, surfaced as a LinkageError, not a
    // catchable Exception). If that happens once, analysis is disabled rather than throwing on every edit.
    // On a JVM (desktop) this never trips. Completion is unaffected; it uses the low-level compiler path.
    //
    // Tracked PER LANGUAGE: now that one engine serves Java/Kotlin/XML, a LinkageError from one backend
    // (in practice only JDT's ASTParser) must disable *that* language alone — never silence the others.
    private val analysisUnavailable = ConcurrentHashMap.newKeySet<LanguageId>()

    /** Whether analysis for [file]'s language has been disabled by a prior unrecoverable error. */
    private fun analysisDisabled(file: Path): Boolean = languageFor(file) in analysisUnavailable

    /** Run the full pipeline (per-language analyzers + diagnostic providers, suppression + profile) over
     *  [file]'s live buffer. ONE engine serves Java/Kotlin/XML: the environment picks the analyzer by the
     *  file's language and each provider is dispatched by its declared `languages`, so there are no more
     *  per-language special cases here. `suspend` because a provider may genuinely leave the worker mid-run
     *  (out-of-process work off the engine worker). `moduleForEditableFile` (not `moduleForFile`) gates so
     *  XML resource files and the manifest — which sit outside the source roots — still analyze. */
    suspend fun analyzeDiagnostics(file: Path, text: String): List<dev.ide.analysis.Diagnostic> {
        if (analysisDisabled(file) || moduleForEditableFile(file) == null) return emptyList()
        updateDocument(file, text)
        return try {
            analysisEngine.analyzeNow(store.vfs.fileFor(file))
        } catch (e: LinkageError) {
            analysisUnavailable.add(languageFor(file))
            emptyList()
        }
    }

    // --- Compose preview (editor integration; see docs/compose-interpreter.md) ---

    /** Optional on-device Compose render host (set by :ide-android after bootstrap). When null, a preview
     *  "run" reports interpretability only. */
    @Volatile
    var composePreviewRunner: ComposePreviewRunner? = null

    /** The `@Preview @Composable` functions in [file]'s live buffer [text] — the editor's preview targets. */
    fun composePreviews(file: Path, text: String): List<dev.ide.lang.kotlin.interp.PreviewInfo> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return analyzer.composePreviews(vf)
    }

    /** Gutter inheritor ("implementations / is subclassed") markers for [file]'s live buffer — one per
     *  inheritable Kotlin type that has direct subtypes in the index. Empty for non-Kotlin files or before
     *  the subtype index has built. */
    fun inheritorMarkers(file: Path, text: String): List<dev.ide.lang.kotlin.InheritorMarker> {
        val module = moduleForEditableFile(file) ?: return emptyList()
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return emptyList()
        val vf = store.vfs.fileFor(file)
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        return analyzer.inheritorMarkers(vf)
    }

    /** Resolve an inheritor type [fqn] (from an [inheritorMarkers] target) to its source location for
     *  go-to-implementation, bound to [contextFile]'s module (whose source model spans its dependency
     *  modules). Null when [fqn] is classpath-only — no navigable project source. */
    fun implementationLocation(contextFile: Path, fqn: String): Pair<Path, Int>? {
        val module = moduleForEditableFile(contextFile) ?: return null
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return null
        return analyzer.declarationLocation(fqn)?.let { (vf, off) -> Paths.get(vf.path) to off }
    }

    /**
     * The cross-MODULE-expanded preview model for [vf] (already parsed in [entry]'s analyzer): seed from the
     * entry file, then run the reachable-declaration expansion across [module] PLUS its transitive dependency
     * MODULES — so a `data class`/helper declared in a dependency module is lowered + merged and the interpreter
     * can construct/call it (not just same-module siblings).
     *
     * Resolution is OWNERSHIP-routed: a module's source model already spans its dependency modules' sources (see
     * [ModuleCompilationContext]), so several modules' analyzers can SEE the same dependency file. We LOCATE a
     * reached declaration via any module that can see it (the entry module first), then LOWER it with the
     * analyzer of the module that actually OWNS the file (its own source root contains it) — so a dependency
     * file resolves against ITS module's full classpath (incl. that module's implementation-only deps), not the
     * entry module's narrower view. Each module's analyzer is the warm, cached one; a dependency module's
     * analyzer models its module from disk + overlay, so it lowers a file even when that file was never opened.
     */
    private fun previewModelFor(
        module: Module, vf: VirtualFile, entry: KotlinSourceAnalyzer,
    ): dev.ide.lang.kotlin.interp.PreviewModel? {
        // The entry module first, then its transitive dependency modules — both for LOCATING a declaration
        // (try the entry's view first) and for OWNERSHIP routing (a file under a module's own source roots is
        // lowered by that module's analyzer).
        val kotlinModules = moduleBuildClosure(module).mapNotNull { m ->
            (analyzerFor(
                m, KotlinLanguageBackend.LANGUAGE_ID
            ) as? KotlinSourceAnalyzer)?.let {
                Triple(
                    m,
                    it,
                    sourceRoots(m).map { r -> r.normalize() })
            }
        }

        fun lowerByOwner(pf: dev.ide.lang.kotlin.symbols.KotlinSymbolService.PreviewSourceFile): dev.ide.lang.kotlin.interp.PreviewFileModel? {
            val path = runCatching { Paths.get(pf.file.path).normalize() }.getOrNull()
            // The module whose OWN source root contains the file lowers it; fall back to the entry analyzer
            // when no module claims it (a path-shape mismatch must never silently drop a declaration).
            val owner = path?.let { p ->
                kotlinModules.firstOrNull { (_, _, roots) ->
                    roots.any {
                        p.startsWith(it)
                    }
                }?.second
            }
            return (owner ?: entry).loweredFile(pf)
        }

        val provider = object : dev.ide.lang.kotlin.interp.PreviewDeclProvider {
            override fun fileDeclaringType(fqn: String): dev.ide.lang.kotlin.interp.PreviewFileModel? =
                kotlinModules.firstNotNullOfOrNull { (_, a, _) -> a.findDeclaringTypeFile(fqn) }
                    ?.let(::lowerByOwner)

            override fun filesDeclaringFunction(name: String): List<dev.ide.lang.kotlin.interp.PreviewFileModel> =
                kotlinModules.flatMap { (_, a, _) -> a.findDeclaringFunctionFiles(name) }
                    .distinctBy { it.file.path }.mapNotNull(::lowerByOwner)
        }
        return entry.lowerFileWithDeps(vf, provider)
    }

    /**
     * Run the `@Preview` composable [functionName] in [file] (buffer [text]): lower the file, verify the
     * preview is fully interpretable, then render it through the injected [composePreviewRunner] (on device)
     * — or, with no runner wired, report that it is interpretable.
     */
    suspend fun runComposePreview(
        file: Path, text: String, functionName: String
    ): PreviewRunResult {
        val module =
            moduleForEditableFile(file) ?: return PreviewRunResult(false, "No module for this file")
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return PreviewRunResult(
            false, "Not a Kotlin file"
        )
        val vf = store.vfs.fileFor(file)
        analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        if (analyzer.hasSyntaxErrors(vf)) return PreviewRunResult(
            false, "the file has syntax errors — fix them to preview"
        )
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, 0) ?: return PreviewRunResult(
            false, "`$functionName` not found as a @Composable"
        )
        if (!entry.isComplete) {
            val why = entry.diagnostics.joinToString("; ") { it.reason }
                .ifBlank { "unsupported constructs" }
            return PreviewRunResult(false, "Cannot preview `$functionName`: $why")
        }
        composePreviewRunner?.let { return it.render(entry, program) }
        return PreviewRunResult(
            true, "`$functionName` is interpretable — on-device rendering coming soon"
        )
    }

    /**
     * Whether [file]'s module can resolve library composables yet. False while the workspace index is still
     * building on first launch (real project: library composables resolve to 0 candidates until it settles) or
     * while the hidden Learn Compose scratch's `androidx.compose.*` AARs are still attaching. The preview host
     * polls this so a first-run failure shows a transient "Preparing" state (and retries once ready) instead of
     * latching into a permanent `unresolved/ambiguous call` / `not a .value delegate` error.
     */
    fun composePreviewReady(file: Path): Boolean {
        val module = moduleForEditableFile(file) ?: return true
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return true
        if (!analyzer.classpathReady()) return false
        // The hidden Learn scratch resolves via the synchronous ClasspathReader (index=null → classpathReady()
        // is trivially true), so ALSO require the Compose runtime to have attached there (the one-time download
        // may still be in flight on first run). A real project gates on the index alone.
        val isScratch = store.rootPath.toString().replace('\\', '/').contains("/.scratch/")
        return !isScratch || analyzer.composeRuntimeAttached()
    }

    /** Why [functionName] in [file] (buffer [text]) isn't interpretable yet: each lowering diagnostic as
     *  `"reason: \"offending source\""`. Empty when it's fully interpretable (or not found). The preview panel
     *  shows these so an un-renderable preview explains the unsupported construct instead of a bare message. */
    fun composePreviewDiagnostics(
        file: Path, text: String, functionName: String, arity: Int = 0
    ): List<String> = try {
        val module = moduleForEditableFile(file) ?: return listOf("no module owns this file")
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return listOf("not a Kotlin file")
        val vf = store.vfs.fileFor(file)
        analyzer.incrementalParser.parseFull(
            EditorDocument(
                vf, docVersion.incrementAndGet(), text
            )
        )
        if (analyzer.hasSyntaxErrors(vf)) return listOf("the file has syntax errors — fix them to preview")
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, arity)
            ?: return listOf("`$functionName` not found as a @Composable (lowered: ${program.keys.joinToString()})")
        // Mirror the gate in [lowerComposePreview]: a preview is blocked when the entry OR any source class it
        // transitively reaches fails to lower. Report BOTH so the reason is never invisible. The entry's own
        // diagnostics slice from this file's text; a reachable class's offsets are into ANOTHER file (e.g. a
        // helper `class` in a sibling file), so those are reported by class name + reason without a snippet.
        val entryProblems = entry.diagnostics.map { d ->
            val snippet = text.substring(
                d.source.start.coerceIn(0, text.length), d.source.end.coerceIn(0, text.length)
            ).replace('\n', ' ').trim()
            if (snippet.isBlank()) d.reason else "${d.reason}: \"$snippet\""
        }
        val classes = model?.classes ?: emptyList()
        val reachable = dev.ide.lang.kotlin.interp.reachableSourceClasses(entry, program, classes)
        val classProblems = classes.filter { it.fqn in reachable && !it.isComplete }.flatMap { c ->
            (c.diagnostics + c.methods.values.flatMap { it.diagnostics }).map { "in ${c.simpleName}: ${it.reason}" }
        }
        (entryProblems + classProblems)
            .ifEmpty { listOf("`$functionName` lowered with no diagnostics — it may render; if not, the failure is in the render path") }
    } catch (t: Throwable) {
        // NEVER return empty on a failure path — a bare "can't be interpreted" with no reason is useless.
        listOf("analysis failed: ${t::class.java.simpleName}: ${t.message ?: "no message"}")
    }

    /** Lower the `@Preview` composable [functionName] in [file] (buffer [text]) to a renderable tree + the
     *  file's program (for its source calls), or null when not found / not fully interpretable. The
     *  on-device render host calls this, then composes [LoweredComposePreview] via the interpreter. */
    fun lowerComposePreview(
        file: Path, text: String, functionName: String, arity: Int = 0
    ): LoweredComposePreview? = dev.ide.lang.kotlin.KotlinPerf.trace("kt.lowerPreview") {
        val module = moduleForEditableFile(file) ?: return null
        val analyzer = analyzerFor(
            module, KotlinLanguageBackend.LANGUAGE_ID
        ) as? KotlinSourceAnalyzer ?: return null
        val vf = store.vfs.fileFor(file)
        dev.ide.lang.kotlin.KotlinPerf.span("parse") {
            analyzer.incrementalParser.parseFull(EditorDocument(vf, docVersion.incrementAndGet(), text))
        }
        // A file with syntax errors mis-shapes declarations when parsed error-tolerantly — interpreting it
        // builds a garbage program that crashes the real Compose runtime in a phase nothing can catch. Don't
        // render it; the editor's diagnostics already flag the errors and the preview shows a "fix errors" state.
        if (analyzer.hasSyntaxErrors(vf)) return null
        // Cross-file AND cross-module: the program + classes include every project-source type/top-level
        // function the preview transitively reaches in OTHER files — same module or a dependency module — so a
        // `data class`/helper declared elsewhere is constructible/callable rather than crashing the render with
        // a missing class.
        val model = previewModelFor(module, vf, analyzer) ?: return null
        val program = model.program
        val entry =
            previewEntry(program, functionName, arity)?.takeIf { it.isComplete } ?: return null
        // Every source type the preview can actually REACH must lower cleanly too (a malformed `data class` it
        // constructs would otherwise build wrong-typed instances). Scope the check to reachable types only — an
        // unrelated class in the same file (e.g. a `MainActivity` whose `onCreate` uses a construct the
        // interpreter doesn't model) is never instantiated by the preview, so it must not block rendering.
        val classes = model.classes
        val reachable = dev.ide.lang.kotlin.interp.reachableSourceClasses(entry, program, classes)
        if (classes.any { it.fqn in reachable && !it.isComplete }) return null
        val parameter = resolvePreviewParameter(analyzer, vf, functionName, arity, classes)
        LoweredComposePreview(entry, program, classes, parameter)
    }

    /** The lowered preview function for [functionName] at [arity] (a `@PreviewParameter` preview has arity > 0);
     *  falls back to any arity of that name so a stale arity still resolves. */
    private fun previewEntry(
        program: Map<String, ResolvedFunction>, functionName: String, arity: Int,
    ): ResolvedFunction? = program["$functionName/$arity"] ?: program["$functionName/0"]
    ?: program.entries.firstOrNull { it.key.substringBeforeLast('/') == functionName }?.value

    /** Resolve the `@PreviewParameter` provider for [functionName]/[arity] (if any) to something the renderer
     *  can instantiate: the lowered source [ResolvedClass] when it's project source, else a best-effort FQN. */
    private fun resolvePreviewParameter(
        analyzer: KotlinSourceAnalyzer,
        vf: VirtualFile, functionName: String, arity: Int,
        classes: List<ResolvedClass>,
    ): LoweredPreviewParameter? {
        val info = analyzer.composePreviews(vf)
            .firstOrNull { it.functionName == functionName && it.arity == arity }?.parameter
            ?: return null
        val source =
            classes.firstOrNull { it.simpleName == info.providerName || it.fqn == info.providerName }
        return LoweredPreviewParameter(
            providerSimpleName = info.providerName,
            providerFqn = source?.fqn ?: analyzer.previewProviderFqn(vf, info.providerName),
            providerClass = source,
            limit = info.limit,
        )
    }

    /**
     * The project library inputs the on-device Compose preview needs to make the user's library composables
     * (`Text`, third-party widgets, sibling library modules) callable: [file]'s module compile classpath
     * (transitive), the bundled `android.jar`, the module's `minSdk`, and a content-stable [fingerprint] (jar
     * paths + sizes) the launcher keys its dex cache on. The launcher (`:ide-android`) dexes [jars] once,
     * builds a `DexClassLoader` (parent = the IDE app loader → shared Compose runtime), and hands it to the
     * interpreter so library calls dispatch against the project's real libraries. Null when [file] isn't in a
     * Kotlin/Android module. Desktop doesn't use this (it resolves against Compose-for-Desktop).
     */
    fun composePreviewLibs(file: Path): ComposePreviewLibs? {
        val module = moduleForEditableFile(file) ?: moduleForFile(file) ?: return null
        val cp = runCatching {
            ModuleCompilationContext.create(
                store.workspace, module, activeConfigs(module)
            ).classpath
        }.getOrNull() ?: return null
        val jars = cp.entries.map { Paths.get(it.root.path) }
            .filter { it.toString().endsWith(".jar") && Files.exists(it) }.distinct()
        if (jars.isEmpty()) return null
        val fingerprint = jars.sortedBy { it.toString() }
            .joinToString("|") { "$it:${runCatching { Files.size(it) }.getOrDefault(-1L)}" }
            .hashCode().toString(16)
        val minApi = module.facets.get(AndroidFacet.KEY)?.minSdk ?: 21
        val cacheDir = store.rootPath.resolve(".platform").resolve("caches").resolve("preview-libs")
        return ComposePreviewLibs(jars, fingerprint, previewAndroidJar(), minApi, cacheDir)
    }


    /**
     * Code actions (quick-fixes + caret intentions) available for [file]'s live buffer [text] over the
     * selection `[start, end)` — what the editor lightbulb / Alt-Enter menu lists. Stable order, so the host
     * round-trips a chosen action back through [applyEditorAction] by its list [Int] index.
     */
    fun editorActions(
        file: Path, text: String, start: Int, end: Int
    ): List<dev.ide.analysis.QuickFix> {
        if (analysisDisabled(file) || moduleForEditableFile(file) == null) return emptyList()
        updateDocument(file, text)
        return try {
            runSync {
                analysisEngine.editorActionsAt(
                    store.vfs.fileFor(file), TextRange(start, end)
                )
            }
        } catch (e: LinkageError) {
            analysisUnavailable.add(languageFor(file))
            emptyList()
        }
    }

    /**
     * Compute (don't apply) the edits of the action at [index] in [editorActions]'s list for [file]'s buffer
     * [text] at `[start, end)`, returning the document edits for [file] itself — the editor applies them to
     * its buffer (the action round-trip, like a block edit). Edits in other files (rare; none of the
     * built-ins do this) are not returned here.
     */
    fun applyEditorAction(
        file: Path, text: String, start: Int, end: Int, index: Int
    ): List<DocumentEdit> {
        if (analysisDisabled(file) || moduleForEditableFile(file) == null) return emptyList()
        updateDocument(file, text)
        val vf = store.vfs.fileFor(file)
        return try {
            runSync {
                analysisEngine.computeActionEdits(
                    vf, TextRange(start, end), index
                )
            }.edits.entries.firstOrNull { it.key.path == vf.path }?.value ?: emptyList()
        } catch (e: LinkageError) {
            analysisUnavailable.add(languageFor(file))
            emptyList()
        }
    }

    // ---- rename refactoring ----------------------------------------------------------------------

    /** The renameable symbol under the caret (its current name + a kind label), or null. Java only. */
    fun prepareRename(file: Path, text: String, offset: Int): RenameInfo? {
        if (!file.toString().endsWith(".java") || analysisDisabled(file)) return null
        val module = moduleForFile(file) ?: return null
        val analyzer = analyzerFor(module) as? JdtSourceAnalyzer ?: return null
        return try {
            JdtRename.targetAt(analyzer.parse(store.vfs.fileFor(file), text), offset)
                ?.let { RenameInfo(it.oldName, it.kind) }
        } catch (e: LinkageError) {
            analysisUnavailable.add(languageFor(file)); null
        }
    }

    /**
     * Rename the symbol under the caret to [newName] across the whole project: resolve the target, find every
     * reference (binding-key match; a file-local symbol stays in its own file, otherwise project `.java` files
     * are pre-filtered by name then parsed), then apply a multi-file edit to disk + the editor overlay. When a
     * top-level public type whose name matches its file is renamed, the backing `.java` file is renamed too
     * (its new path is returned so the editor can reopen it). Re-indexes and invalidates analyzers.
     */
    suspend fun rename(file: Path, text: String, offset: Int, newName: String): RenameOutcome {
        val name = newName.trim()
        if (!isValidJavaIdentifier(name)) return RenameOutcome(
            false, "'$newName' is not a valid Java identifier."
        )
        if (analysisDisabled(file)) return RenameOutcome(false, "Java analysis is unavailable.")
        val module = moduleForFile(file) ?: return RenameOutcome(
            false, "This file is not in a source module."
        )
        val analyzer = analyzerFor(module) as? JdtSourceAnalyzer ?: return RenameOutcome(
            false, "Rename is only supported for Java."
        )
        val fileAbs = file.toAbsolutePath().normalize()

        val target: dev.ide.lang.jdt.rename.RenameTarget
        val editsByPath = LinkedHashMap<Path, List<DocumentEdit>>()
        var occurrences = 0
        try {
            val parsed = analyzer.parse(store.vfs.fileFor(file), text)
            target = JdtRename.targetAt(parsed, offset) ?: return RenameOutcome(
                false, "Place the caret on a symbol to rename."
            )
            if (name == target.oldName) return RenameOutcome(
                false, "The new name is the same as the current one."
            )

            fun editsFor(ranges: List<TextRange>) =
                ranges.map { DocumentEdit(it.start, it.end - it.start, name) }
            if (target.fileLocal) {
                val ranges = JdtRename.referencesIn(parsed, target)
                if (ranges.isNotEmpty()) {
                    editsByPath[fileAbs] = editsFor(ranges); occurrences += ranges.size
                }
            } else {
                for (cand in projectJavaFiles()) {
                    val candAbs = cand.toAbsolutePath().normalize()
                    val candText =
                        openDocuments[candAbs] ?: runCatching { cand.readText() }.getOrNull()
                        ?: continue
                    if (!candText.contains(target.oldName)) continue // cheap pre-filter before the binding parse
                    val candParsed = if (candAbs == fileAbs) parsed else {
                        val m = moduleForFile(cand) ?: continue
                        val a = analyzerFor(m) as? JdtSourceAnalyzer ?: continue
                        a.parse(store.vfs.fileFor(cand), candText)
                    }
                    val ranges = JdtRename.referencesIn(candParsed, target)
                    if (ranges.isNotEmpty()) {
                        editsByPath[candAbs] = editsFor(ranges); occurrences += ranges.size
                    }
                }
            }
        } catch (e: LinkageError) {
            analysisUnavailable.add(languageFor(file))
            return RenameOutcome(false, "Java analysis is unavailable.")
        }
        if (editsByPath.isEmpty()) return RenameOutcome(
            false, "No occurrences of '${target.oldName}' found."
        )

        // Apply each file's edits descending (offsets stay valid), writing disk + the live overlay together.
        for ((path, edits) in editsByPath) {
            val current =
                openDocuments[path] ?: runCatching { path.readText() }.getOrNull() ?: continue
            val sb = StringBuilder(current)
            for (e in edits.sortedByDescending { it.offset }) {
                val s = e.offset.coerceIn(0, sb.length)
                val en = (e.offset + e.oldLength).coerceIn(s, sb.length)
                sb.replace(s, en, e.newText.toString())
            }
            val updated = sb.toString()
            openDocuments[path] = updated
            runCatching { path.parent?.let { Files.createDirectories(it) }; path.writeText(updated) }
        }

        // Rename the backing file when a top-level public type's name matched the file name (Java convention).
        var newPath: Path? = null
        if (target.isType && fileAbs.fileName?.toString() == "${target.oldName}.java") {
            val dest = fileAbs.resolveSibling("$name.java")
            if (!Files.exists(dest)) runCatching {
                Files.move(fileAbs, dest)
                openDocuments.remove(fileAbs)?.let { openDocuments[dest] = it }
                newPath = dest
            }
        }

        // One batched mutation event: the hub coalesces the reaction (a multi-file edit / file rename
        // invalidates analyzers + synthetics and re-syncs the index exactly once).
        events.filesMutated(editsByPath.keys.toList(), newPath?.let { fileAbs to it })
        return RenameOutcome(
            true,
            "Renamed '${target.oldName}' to '$name'",
            occurrences,
            editsByPath.size,
            newPath?.toString()
        )
    }

    // ---- file & package operations (delete / rename / move / copy), for files AND directories/packages ----

    /** Absolute-normalized form, matching how [openDocuments] is keyed. */
    private fun normPath(p: Path): Path = p.toAbsolutePath().normalize()

    /** Drop every open-document overlay at [root] or under it (after a delete). */
    private fun dropOverlaysUnder(root: Path) {
        val r = normPath(root)
        openDocuments.keys.filter { it == r || it.startsWith(r) }
            .forEach { openDocuments.remove(it) }
    }

    /** Re-point open-document overlays from [from] (a file or directory) to [to] after a move/rename. */
    private fun rekeyOverlays(from: Path, to: Path) {
        val f = normPath(from)
        val t = normPath(to)
        for (k in openDocuments.keys.filter { it == f || it.startsWith(f) }) {
            val dest = if (k == f) t else t.resolve(f.relativize(k))
            openDocuments.remove(k)?.let { openDocuments[normPath(dest)] = it }
        }
    }

    /** Offset of the identifier in a top-level `class/interface/enum/record TypeName` declaration, or null. */
    private fun javaTypeNameOffset(text: String, typeName: String): Int? =
        Regex("\\b(?:class|interface|enum|record)\\s+(${Regex.escape(typeName)})\\b").find(text)?.groups?.get(
            1
        )?.range?.first

    // A leading `package a.b.c;` declaration (the first one; package must precede all type decls in Java).
    private val PACKAGE_DECL = Regex("""(?m)^[ \t]*package[ \t]+([\w.]+)[ \t]*;[ \t]*\r?\n?""")

    /**
     * The Java package a directory maps to, by its position under the most-specific source root that contains
     * it: `<root>/com/example` → `com.example`. Returns `""` for a source root itself (the default package),
     * or null when [dir] sits under no source root (so the package can't be derived — leave the file alone).
     */
    private fun packageForDir(dir: Path): String? {
        val d = normPath(dir)
        val root =
            modules().asSequence().flatMap { sourceRoots(it).asSequence() }.map { normPath(it) }
                .filter { d == it || d.startsWith(it) }.maxByOrNull { it.nameCount } ?: return null
        if (d == root) return ""
        val rel = root.relativize(d)
        return (0 until rel.nameCount).joinToString(".") { rel.getName(it).toString() }
    }

    /** [text] with its `package` declaration set to [pkg] (`""` = default package), or null if already so. */
    private fun withPackageDeclaration(text: String, pkg: String): String? {
        val existing = PACKAGE_DECL.find(text)
        return when {
            existing != null && existing.groupValues[1] == pkg -> null
            existing != null && pkg.isEmpty() -> text.removeRange(existing.range) // → default package: drop the line
            existing != null -> text.replaceRange(existing.groups[1]!!.range, pkg)
            pkg.isEmpty() -> null // no declaration and the default package is wanted → nothing to do
            else -> "package $pkg;\n\n$text"
        }
    }

    /**
     * After a `.java` file (or a directory of them) lands at [dest], rewrite each moved file's `package`
     * declaration to match its new source-root-relative location, and — when [updateReferences] (a move, not a
     * copy) — rewrite explicit `import old.Type;` / `import static old.Type.…;` statements across the project to
     * the new package. A file outside any source root, or already in the right package, is left untouched.
     * Fully-qualified usages and same-package (un-imported) references are not rewritten — the compiler flags
     * those for the import quick-fix. Touches disk and the live overlay together; callers do the reindex.
     */
    private fun fixPackagesAfterRelocation(dest: Path, updateReferences: Boolean) {
        val movedFiles = when {
            Files.isDirectory(dest) -> runCatching {
                Files.walk(dest).use { s ->
                    s.filter { it.toString().endsWith(".java") }.collect(Collectors.toList())
                }
            }.getOrDefault(emptyList())

            dest.toString().endsWith(".java") -> listOf(dest)
            else -> return
        }
        val typeRenames = LinkedHashMap<String, String>() // old FQN → new FQN, for the import sweep
        val movedKeys = HashSet<Path>()
        for (f in movedFiles) {
            val parent = f.parent ?: continue
            val newPkg =
                packageForDir(parent) ?: continue // outside a source root → can't derive a package
            val key = normPath(f)
            movedKeys.add(key)
            val text = openDocuments[key] ?: runCatching { f.readText() }.getOrNull() ?: continue
            val oldPkg = PACKAGE_DECL.find(text)?.groupValues?.get(1) ?: ""
            if (oldPkg == newPkg) continue
            val updated = withPackageDeclaration(text, newPkg) ?: continue
            openDocuments[key] = updated
            runCatching { f.writeText(updated) }
            if (updateReferences) for (type in topLevelTypeNames(text, f)) {
                val oldFqn = if (oldPkg.isEmpty()) type else "$oldPkg.$type"
                val newFqn = if (newPkg.isEmpty()) type else "$newPkg.$type"
                typeRenames[oldFqn] = newFqn
            }
        }
        if (updateReferences && typeRenames.isNotEmpty()) updateImportsForMovedTypes(
            typeRenames, movedKeys
        )
    }

    /** Top-level type names declared in [text] (binding-free JDT parse), falling back to the file-name type. */
    private fun topLevelTypeNames(text: String, file: Path): List<String> = runCatching {
        JavaSourceIndexer.parse(text).decls.filter { it.container == null && it.kind in TOP_LEVEL_TYPE_KINDS }
            .map { it.name }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(
        file.fileName.toString().removeSuffix(".java")
    ).filter { isValidJavaIdentifier(it) }

    /** Rewrite explicit imports of the moved types ([renames]: old FQN → new FQN) across the project. */
    private fun updateImportsForMovedTypes(renames: Map<String, String>, skip: Set<Path>) {
        for (file in projectJavaFiles()) {
            val key = normPath(file)
            if (key in skip) continue
            val text = openDocuments[key] ?: runCatching { file.readText() }.getOrNull() ?: continue
            var updated = text
            for ((oldFqn, newFqn) in renames) {
                updated = updated.replace("import $oldFqn;", "import $newFqn;")
                    .replace("import static $oldFqn.", "import static $newFqn.")
            }
            if (updated != text) {
                openDocuments[key] = updated
                runCatching { file.writeText(updated) }
            }
        }
    }

    /**
     * Delete a file or directory (recursively). Drops any open-document overlays under it, then invalidates
     * analyzers and resyncs the index. Returns true on success.
     */
    fun deletePath(target: Path): Boolean {
        val abs = normPath(target)
        val ok = runCatching {
            if (Files.isDirectory(abs)) abs.toFile().deleteRecursively() else Files.deleteIfExists(
                abs
            )
        }.getOrDefault(false)
        if (ok) events.fileDeleted(abs) // reaction: drop overlays under it, invalidate analyzers, re-sync
        return ok
    }

    /**
     * Rename a file or directory in place (same parent) to [newName]. For a Java source file whose public type
     * matches the file name, this delegates to the symbol [rename] so the type and all references update too
     * (the backing file is renamed as part of that). Otherwise it is a plain filesystem rename. [newName] is
     * the full new name (with extension for files).
     */
    suspend fun renameFile(target: Path, newName: String): RenameOutcome {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) return RenameOutcome(
            false, "'$newName' is not a valid name."
        )
        val abs = normPath(target)
        if (!Files.exists(abs)) return RenameOutcome(false, "'${abs.fileName}' no longer exists.")
        val parent = abs.parent ?: return RenameOutcome(false, "Can't rename the workspace root.")

        // Java class-aware path: when the file actually declares a top-level type matching its name, rename the
        // type (and every reference) — which also moves the backing file — instead of a bare filesystem rename.
        if (!analysisDisabled(abs) && abs.toString().endsWith(".java")) {
            val oldType = abs.fileName.toString().removeSuffix(".java")
            val newType = name.removeSuffix(".java")
            if (isValidJavaIdentifier(newType)) {
                val text = openDocuments[abs] ?: runCatching { abs.readText() }.getOrNull()
                val offset = text?.let { javaTypeNameOffset(it, oldType) }
                if (text != null && offset != null) return rename(abs, text, offset, newType)
            }
        }

        val dest = parent.resolve(name)
        if (dest == abs) return RenameOutcome(false, "The new name is the same as the current one.")
        if (Files.exists(dest)) return RenameOutcome(false, "'$name' already exists here.")
        return runCatching {
            Files.move(abs, dest)
            events.fileMoved(abs, dest) // reaction: re-key overlays, invalidate analyzers, re-sync
            RenameOutcome(true, "Renamed to '$name'", newPath = dest.toString())
        }.getOrElse { RenameOutcome(false, "Rename failed: ${it.message}") }
    }

    /** Move a file or directory into [destDir]. Returns the new path, or null on conflict/failure. */
    fun movePath(target: Path, destDir: Path): Path? {
        val abs = normPath(target)
        val dir = normPath(destDir)
        val dest = dir.resolve(abs.fileName)
        if (Files.exists(dest) || dest == abs || dir == abs || dir.startsWith(abs)) return null // no overwrite / move-into-self
        return runCatching {
            Files.createDirectories(dir)
            Files.move(abs, dest)
            // The package-line/import rewrite is mutation mechanics and needs the overlays re-keyed first;
            // the invalidation chain then runs as the hub's FileMoved reaction.
            rekeyOverlays(abs, dest)
            fixPackagesAfterRelocation(dest, updateReferences = true)
            events.fileMoved(abs, dest)
            dest
        }.getOrNull()
    }

    /** Copy a file or directory into [destDir]. Returns the new path, or null on conflict/failure. */
    fun copyPath(target: Path, destDir: Path): Path? {
        val abs = normPath(target)
        val dir = normPath(destDir)
        val dest = dir.resolve(abs.fileName)
        if (Files.exists(dest) || dest.startsWith(abs)) return null
        return runCatching {
            Files.createDirectories(dir)
            if (Files.isDirectory(abs)) abs.toFile()
                .copyRecursively(dest.toFile(), overwrite = false)
            else Files.copy(abs, dest)
            // The copy lands in a new package; fix its own `package` line, but leave references to the
            // original alone (a copy is a new type, not a moved one).
            fixPackagesAfterRelocation(dest, updateReferences = false)
            // Publish the real created-file set (a copied package = one event per file, coalesced reaction).
            events.filesCreated(filesUnder(dest))
            dest
        }.getOrNull()
    }

    /** [root] itself for a file, or every regular file under it for a directory (for created-file events). */
    private fun filesUnder(root: Path): List<Path> =
        if (Files.isDirectory(root)) {
            runCatching {
                Files.walk(root).use { s ->
                    // Collectors.toList, not Stream.toList: the latter is Java 16+, absent on ART/API 26.
                    s.filter { Files.isRegularFile(it) }.collect(Collectors.toList())
                }
            }.getOrDefault(listOf(root))
        } else {
            listOf(root)
        }

    /** Every `.java` file across the workspace's modules (for the project-wide reference sweep). */
    private fun projectJavaFiles(): List<Path> {
        val out = ArrayList<Path>()
        for (m in modules()) for (root in sourceRoots(m)) {
            if (!Files.isDirectory(root)) continue
            runCatching {
                Files.walk(root).use { s ->
                    s.filter { it.toString().endsWith(".java") }.forEach { out.add(it) }
                }
            }
        }
        return out
    }

    private fun isValidJavaIdentifier(s: String): Boolean =
        s.isNotEmpty() && Character.isJavaIdentifierStart(s[0]) && s.drop(1)
            .all { Character.isJavaIdentifierPart(it) } && s !in JAVA_RESERVED

    // ---- XML layout completion metadata ----

    // The SDK-derived metadata (widget/attribute set from attrs.xml + the android.jar hierarchy).
    // A workspace override (`.platform/android-sdk-metadata.txt`, e.g. a different API level generated by the
    // `:android-sdk-metadata` tool) wins; otherwise the asset bundled in android-support is used. Never null,
    // so completion covers the full set out of the box (including parent layout params such as
    // RelativeLayout's layout_below/toEndOf).
    @Volatile
    private var sdkMetadata: AndroidSdkMetadata? = null

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
    private fun customAttrsMetadata(
        module: Module, hierarchy: Map<String, String> = emptyMap()
    ): AndroidSdkMetadata? {
        val dirs =
            runCatching { AndroidResources.resourceDirs(module, store.workspace) }.getOrDefault(
                emptyList()
            )
        val attrs = LinkedHashMap<String, AttrEntry>()
        val styleables = LinkedHashMap<String, StyleableEntry>()
        for (dir in dirs) {
            val valuesDirs = runCatching {
                Files.list(dir).use { it.collect(Collectors.toList()) }
            }.getOrDefault(emptyList())
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values") }
            for (vd in valuesDirs) {
                val files = runCatching {
                    Files.list(vd).use { it.collect(Collectors.toList()) }
                }.getOrDefault(emptyList())
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
        return AndroidSdkMetadata(
            0, attrs, styleables, hierarchy, emptyList(), attrPrefix = "app:",
            viewSubstitutions = AndroidSdkMetadata.APPCOMPAT_SUBSTITUTIONS,
        )
    }

    /**
     * Custom `android.view.View` subclasses on [module]'s library classpath (Maven jars + AAR `classes.jar`),
     * so the layout editor suggests them as element tags by their fully-qualified name (e.g.
     * `com.google.android.material.button.MaterialButton`). Framework widgets come from the SDK metadata; this
     * fills the rest. The bytecode scan is content-fingerprint cached on disk — it only re-runs when the jar
     * set changes — so it does not repeat per analyzer rebuild.
     */
    private fun customViewScan(module: Module): dev.ide.android.support.metadata.CustomViewScanner.Scan {
        val empty = dev.ide.android.support.metadata.CustomViewScanner.Scan(emptyList(), emptyMap())
        val jars = runCatching {
            ModuleCompilationContext.create(
                store.workspace, module, activeConfigs(module)
            ).classpath.entries
        }.getOrDefault(emptyList())
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
            .filter { it.toString().endsWith(".jar") && Files.exists(it) }.distinct()
        if (jars.isEmpty()) return empty
        // Seed the scanner with the framework View hierarchy (a library view's chain bottoms out at a
        // framework base class that isn't in any jar) — simple name → is-it-a-ViewGroup.
        val frameworkWidgets = runCatching {
            sdkLayoutMetadata().childTagsFor(null).associate { it.tag to it.isViewGroup }
        }.getOrDefault(emptyMap())
        val cacheFile =
            store.rootPath.resolve(".platform/caches/custom-views/${module.id.value}.txt")
        return dev.ide.android.support.metadata.CustomViewScanner.cachedScan(
            jars, cacheFile, frameworkWidgets
        )
    }

    /**
     * Project-SOURCE custom `View` subclasses (uncompiled `.java`/`.kt`), discovered via the direct-inheritor
     * `SubtypeIndex` (source producers) seeded from the framework widgets — the source-side complement of
     * [customViewScan] (compiled library/AAR jars). Returns the same [dev.ide.android.support.metadata.CustomViewScanner.Scan]
     * shape (FQN tags + ancestry). Empty until the source subtype index has built ("dumb until indexed").
     */
    private fun sourceCustomViewScan(): dev.ide.android.support.metadata.CustomViewScanner.Scan {
        val empty = dev.ide.android.support.metadata.CustomViewScanner.Scan(emptyList(), emptyMap())
        val seeds = runCatching {
            sdkLayoutMetadata().childTagsFor(null).associate { it.tag to it.isViewGroup }
        }.getOrDefault(emptyMap()).toMutableMap().apply { put("View", false); put("ViewGroup", true) }
        if (seeds.isEmpty()) return empty
        return runCatching {
            dev.ide.android.support.metadata.SourceCustomViewResolver.resolve(
                seeds,
                { id, key -> indexService.exact<dev.ide.index.SubtypeValue>(id, key) },
            )
        }.getOrDefault(empty)
    }

    /**
     * The host the XML lint provider ([dev.ide.lang.xml.lint.XmlDiagnosticProvider]) calls for the data that
     * lives in ide-core, not lang-xml: the SDK widget catalog, the Android resource index/repository, and the
     * `res/` filesystem. Resolves the module per file via [moduleForEditableFile] (XML files live under `res/`,
     * outside the source roots). The detection rules + fix construction live in lang-xml; this only supplies
     * resources, keeping lang-xml a generic XML backend.
     */
    private inner class IdeXmlResourceHost : dev.ide.lang.xml.lint.XmlResourceHost {
        override fun isViewLike(tag: String): Boolean = this@IdeServices.isViewLike(tag)

        override fun scanResourceReferences(text: String): List<dev.ide.lang.xml.lint.XmlResourceRef> =
            ResourceReferences.scan(text).filter {
                it.isLocal && !it.create && !it.themeAttr && it.type != null && it.name !in setOf(
                    "null", "empty", "undefined"
                )
            }.map {
                dev.ide.lang.xml.lint.XmlResourceRef(
                    it.type!!.rClass, it.name, it.range.first, it.range.last + 1
                )
            }

        // Both go purely through the incremental resource index (project + dependency/AAR res). No repository
        // fallback: while the index is still building, `typeHasAny` is false so the unresolved-resource check
        // stays quiet (never a false positive) until the index is ready: the same "dumb until indexed" rule
        // the other editor features follow.
        override fun typeHasAny(file: VirtualFile, rClass: String): Boolean =
            ResourceType.byRClass(rClass)?.let { indexTypeHasAny(it) } == true

        override fun hasResource(file: VirtualFile, rClass: String, name: String): Boolean {
            val type = ResourceType.byRClass(rClass) ?: return true // unknown type → don't flag
            return indexHasResource(type, name)
        }

        override fun isValueType(rClass: String): Boolean =
            ResourceType.byRClass(rClass)?.isValueType() == true

        override fun appendValueResource(
            file: VirtualFile, rClass: String, name: String, value: String
        ): String {
            val type = ResourceType.byRClass(rClass) ?: return name
            val module = moduleForEditableFile(Paths.get(file.path)) ?: return name
            return this@IdeServices.appendValueResource(module, type, name, value)
        }

        override fun isFileType(rClass: String): Boolean =
            ResourceType.byRClass(rClass)?.isFileResource() == true

        override fun createResourceFile(file: VirtualFile, rClass: String, name: String): String? {
            val type = ResourceType.byRClass(rClass) ?: return null
            val module = moduleForEditableFile(Paths.get(file.path)) ?: return null
            return this@IdeServices.createResourceFile(module, type, name)
        }
    }

    /** True when [target]'s module library classpath includes AppCompat, so its `app:` compat attrs resolve. */
    private fun moduleUsesAppCompat(target: AnalysisTarget): Boolean =
        runCatching {
            ModuleCompilationContext.create(store.workspace, target.module, activeConfigs(target.module)).classpath.entries.any {
                it.root.path.contains("appcompat", ignoreCase = true)
            }
        }.getOrDefault(false)

    // ---- Android resource helpers shared by the XML lint host + go-to-definition ----

    private fun ResourceType.isValueType(): Boolean = this in setOf(
        ResourceType.STRING,
        ResourceType.COLOR,
        ResourceType.DIMEN,
        ResourceType.BOOL,
        ResourceType.INTEGER,
        ResourceType.ID,
    )

    /** A resource that lives as a standalone XML file under `res/<type>/` (so a missing one is created from a
     *  stub, not appended to `res/values`). */
    private fun ResourceType.isFileResource(): Boolean = this in setOf(
        ResourceType.LAYOUT,
        ResourceType.DRAWABLE,
        ResourceType.MENU,
        ResourceType.ANIM,
        ResourceType.ANIMATOR,
    )

    /** A tag that should carry layout params: a known framework widget (from the SDK metadata) or a custom view. */
    private fun isViewLike(tag: String): Boolean =
        sdkLayoutMetadata().isWidgetTag(tag) || tag.contains('.')

    /** Append `<type name="name">value</type>` to the module's `res/values/<file>.xml` (creating it if needed),
     *  de-duplicating the name, then refresh R + the resource index. Returns the (possibly suffixed) name. */
    private fun appendValueResource(
        module: Module, type: ResourceType, name: String, value: String
    ): String {
        val valuesDir = resourceRoots(module).firstOrNull()?.resolve("values") ?: return name
        val target = valuesDir.resolve(valuesFileName(type))
        val existing =
            runCatching { if (Files.exists(target)) target.readText() else null }.getOrNull()
        var unique = name
        var i = 1
        while (existing != null && Regex("name\\s*=\\s*\"${Regex.escape(unique)}\"").containsMatchIn(
                existing
            )
        ) unique = "${name}_${i++}"
        val entry = "    <${type.rClass} name=\"$unique\">${escapeXml(value)}</${type.rClass}>\n"
        runCatching {
            Files.createDirectories(valuesDir)
            if (existing == null) {
                target.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$entry</resources>\n")
            } else {
                val idx = existing.lastIndexOf("</resources>")
                val merged = if (idx >= 0) existing.substring(
                    0, idx
                ) + entry + existing.substring(idx) else existing + entry
                target.writeText(merged)
            }
            // Reaction (res .xml): refresh R + re-index the file.
            if (existing == null) events.fileCreated(target) else events.fileChanged(target)
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

    /** Create `res/<type>/<name>.xml` with a minimal stub for a file resource (layout/drawable/menu/anim),
     *  then refresh R + the resource index. Returns the new file's path, the existing one if it already
     *  exists, or null on failure. */
    private fun createResourceFile(module: Module, type: ResourceType, name: String): String? {
        val resRoot = resourceRoots(module).firstOrNull() ?: return null
        val folder = resRoot.resolve(type.rClass)
        val target = folder.resolve("$name.xml")
        if (Files.exists(target)) return target.toString()
        return runCatching {
            Files.createDirectories(folder)
            target.writeText(resourceFileStub(type))
            events.fileCreated(target) // reaction (res .xml): refresh R + re-index the file
            target.toString()
        }.getOrNull()
    }

    /** A minimal, valid starting document for a newly created file resource of [type]. */
    private fun resourceFileStub(type: ResourceType): String {
        val ns = "http://schemas.android.com/apk/res/android"
        val head = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        return head + when (type) {
            ResourceType.LAYOUT -> "<FrameLayout xmlns:android=\"$ns\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</FrameLayout>\n"

            ResourceType.DRAWABLE -> "<shape xmlns:android=\"$ns\" android:shape=\"rectangle\">\n    <solid android:color=\"#FF000000\" />\n</shape>\n"

            ResourceType.MENU -> "<menu xmlns:android=\"$ns\" xmlns:app=\"http://schemas.android.com/apk/res-auto\">\n\n</menu>\n"

            ResourceType.ANIM, ResourceType.ANIMATOR -> "<set xmlns:android=\"$ns\">\n\n</set>\n"

            else -> "<resources>\n\n</resources>\n"
        }
    }

    /**
     * Go-to-definition for the Android resource the caret sits on — an `@type/name` reference in res XML, or
     * an `R.type.name` access in Java — resolved against the module's merged resources to its declaration
     * (file + offset). Local references only; framework (`@android:`) / other-namespace refs return null.
     */
    fun definitionAt(file: Path, text: String, offset: Int): Pair<Path, Int>? {
        val isXml = file.fileName?.toString()?.endsWith(".xml") == true
        val module =
            (if (isXml) moduleForResourceFile(file) else moduleForFile(file)) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        val (type, name) = (if (isXml) xmlResourceRefAt(text, offset) else rClassRefAt(
            text, offset
        )) ?: return null
        // Resolved purely through the resource index, which carries the declaring file + offset. No repository
        // fallback: an as-yet-unindexed resource simply has no go-to target until the index catches up.
        return indexDefinition(type, name)
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

    /** Resolve a resource to its declaration (file + offset) via the resource index, or null. */
    private fun indexDefinition(type: ResourceType, name: String): Pair<Path, Int>? =
        indexService.exact<ResourceDeclValue>(
            AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)
        ).firstOrNull()?.let { Paths.get(it.filePath) to it.offset }

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
        return runCatching {
            DrawablePreviewParser.parse(
                text, drawableResolver(module)
            )
        }.getOrNull()
    }

    /** The `<color>` entries of a `res/values` XML [file] ([text] = live buffer), resolved to ARGB for swatches. */
    fun colorResources(file: Path, text: String): List<ColorEntry> {
        val resolver =
            moduleForResourceFile(file)?.let { drawableResolver(it) } ?: DrawableResolver.NONE
        return runCatching { ColorResources.parse(text, resolver) }.getOrDefault(emptyList())
    }

    /** Raw bytes of a resource file (for bitmap preview); null if unreadable. */
    fun resourceBytes(file: Path): ByteArray? = runCatching { Files.readAllBytes(file) }.getOrNull()

    /**
     * Build the owned render tree + system chrome for a layout XML [file] (live buffer [text]) at the given
     * device viewport, for the Preview view. Null if [file] isn't an Android `res/layout` file. Custom views
     * currently render as placeholders (the live bridge is the next phase); built-ins + chrome are rendered.
     */
    fun layoutPreview(
        file: Path, text: String, request: dev.ide.preview.PreviewRequest
    ): dev.ide.preview.LayoutPreviewResult? {
        if (!file.toString().replace('\\', '/').contains("/res/layout")) return null
        val module = moduleForResourceFile(file) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        val cached = resourceRepository(module) ?: return null
        val repo = cached.repo
        val (themeName, title) = manifestThemeAndLabel(module, repo)
        // Real-view ("layoutlib-on-device") path, when requested and a runtime is wired. On success it returns
        // the rendered PNG; on any failure it returns owned rendering annotated with the reason (never blank).
        if (request.realViews && realViewRuntime != null) {
            // realViewPreview publishes its pipeline stage to _realViewProgress (the status chip); clear it
            // here so every exit path (success or owned fallback) leaves the chip idle.
            return try {
                realViewPreview(module, repo, cached.fingerprint, themeName, title, file, text, request)
            } finally {
                _realViewProgress.value = null
            }
        }
        return ownedPreview(module, repo, cached.fingerprint, themeName, title, text, request, extraProblem = null)
    }

    /** Build the owned render tree + chrome (the cross-platform path), optionally appending [extraProblem]. */
    private fun ownedPreview(
        module: Module,
        repo: ResourceRepository,
        fingerprint: String,
        themeName: String?,
        title: String,
        text: String,
        request: dev.ide.preview.PreviewRequest,
        extraProblem: String?,
    ): dev.ide.preview.LayoutPreviewResult? {
        val customViews = if (referencesCustomView(text)) cachedCustomViewFactory(module, repo, fingerprint) else null
        val base = runCatching {
            dev.ide.preview.impl.LayoutPreviewService(
                customViewFactory = customViews ?: dev.ide.preview.impl.CustomViewFactory.NONE
            ).preview(
                xml = text, repo = repo, themeName = themeName, title = title,
                density = request.density, scaledDensity = request.density,
                showChrome = request.showChrome, night = request.night,
                layoutProvider = { name ->
                    repo.definitions(ResourceType.LAYOUT, name)
                        .firstOrNull()?.source?.let { runCatching { it.readText() }.getOrNull() }
                },
            )
        }.getOrNull() ?: return null
        if (extraProblem == null) return base
        return dev.ide.preview.LayoutPreviewResult(
            base.root, base.resources, base.density, base.scaledDensity, base.imageFile,
            base.problems + dev.ide.preview.PreviewProblem("real-view", extraProblem),
        )
    }

    /** Relinks the build's compiled resources with the live editor buffer for the real-view preview, so the
     *  edited (or newly added) layout renders. Null when no Android tooling is wired (desktop). */
    private val previewResourceLinker: dev.ide.android.support.PreviewResourceLinker? by lazy {
        val t = androidTools ?: return@lazy null
        val sdk = AndroidSdk.forDevice(t.androidJar, t.nativeLibDir)
            .takeIf { it.hasNativeTools() } ?: return@lazy null
        val cache = (sharedCachesRoot ?: store.rootPath).resolve("caches").resolve("preview-res")
        dev.ide.android.support.PreviewResourceLinker(
            dev.ide.android.support.tools.Aapt2Subprocess(sdk.aapt2), t.androidJar, cache,
        )
    }

    /**
     * Render [file] with the on-device real-view runtime (the real framework + the project's real libraries).
     * The build's aapt2-linked resources are relinked with the live editor buffer ([previewResourceLinker]) so
     * the edited (or newly added) layout renders, falling back to the build's own `resources.ap_` if the relink
     * fails; the build's `R.jar` is reused on the class loader. On success returns a [LayoutPreviewResult]
     * carrying the rendered PNG; otherwise returns owned rendering annotated with the reason. Never returns null
     * (the caller only enters here with a non-null runtime).
     */
    private fun realViewPreview(
        module: Module,
        repo: ResourceRepository,
        fingerprint: String,
        themeName: String?,
        title: String,
        file: Path,
        text: String,
        request: dev.ide.preview.PreviewRequest,
    ): dev.ide.preview.LayoutPreviewResult? {
        val runtime = realViewRuntime ?: return ownedPreview(module, repo, fingerprint, themeName, title, text, request, null)
        val facet = module.facets.get(AndroidFacet.KEY)
            ?: return ownedPreview(module, repo, fingerprint, themeName, title, text, request, null)
        val activeVariant = AndroidVariants.defaultVariant(module)
        val variant = activeVariant?.name ?: "debug"
        // Relink the live buffer over the project's resources so the preview reflects the edited (or newly
        // added) layout. The linker self-builds its base from the live res tree, so a prior successful build
        // is NOT required and edits to other resources show; it falls back internally to the build's flats.
        val moduleDir = Paths.get(module.outputDir.path).parent.parent
        val manifestPath = dev.ide.android.support.AndroidBuildSystem.mergedManifestPath(module, variant)
            .takeIf { Files.exists(it) } ?: moduleDir.resolve(facet.manifest)
        val resDirs = AndroidResources.resourceDirs(module, store.workspace)
        // The runtime library set + the AAR package names, resolved once. Must be the RUNTIME set (what the APK
        // packages), NOT the compile classpath: a Material widget's `implementation` transitives (e.g.
        // androidx.emoji2, pulled in by AppCompatTextView's EmojiCompat) are on the runtime path but absent from
        // compile, so a compile-only classpath inflates with NoClassDefFoundError. `AndroidLibraries.resolve`
        // gives exactly the runtime/packaged set the build's dexBuilder uses; module outputs cover multi-module
        // + user custom views. Dexed via the shared cache (SharedLibraryDexer), so it's dexed once and reused.
        val explodeRoot = dev.ide.android.support.AndroidBuildSystem.explodedAarPath(module, variant)
        val resolved = runCatching {
            dev.ide.android.support.AndroidLibraries.resolve(module, explodeRoot, activeVariant?.configurations)
        }.getOrNull()
        // Extra R packages: every AAR package (androidx.coordinatorlayout, com.google.android.material, …) plus
        // every OTHER android module's package. A library/framework view references its OWN `R` at inflate time
        // (e.g. CoordinatorLayout → `androidx.coordinatorlayout.R$attr`), and AARs do NOT ship their `R` — the
        // consuming app generates one per package via aapt2 `--extra-packages`. Without these the preview crashes
        // with NoClassDefFoundError for a lib's R class. Mirrors the build's `extraPackages`.
        val extraPackages = ((resolved?.aarPackages ?: emptyList()) +
            modules().mapNotNull { it.facets.get(AndroidFacet.KEY)?.namespace }.filter { it != facet.namespace })
            .distinct()
        // Relink the live buffer over the project's resources so the preview reflects the edited (or newly
        // added) layout. The linker self-builds its base from the live res tree, so a prior successful build
        // is NOT required and edits to other resources show; it falls back internally to the build's flats. It
        // also generates a preview `R.jar` (app + [extraPackages]) with ids matching the relinked arsc, so the
        // preview no longer depends on a prior build's (id-mismatched, possibly absent) R.jar.
        val linked = previewResourceLinker?.link(
            module, variant, resDirs, file, text, manifestPath, facet.namespace, facet.minSdk, facet.targetSdk,
            extraPackages = extraPackages,
            progress = { _realViewProgress.value = PreviewProgress(it) },
        )
        // The resources.ap_ to render against: the live relink, else the build's own, else owned rendering.
        val resourcesAp = linked?.resourcesAp
            ?: dev.ide.android.support.AndroidBuildSystem.resourcesApPath(module, variant).takeIf { Files.exists(it) }
            ?: return ownedPreview(module, repo, fingerprint, themeName, title, text, request,
                "Real-view resources unavailable (${linked?.error ?: "build the project once"}) — showing owned rendering")
        // The R.jar: prefer the relink's (ids match the relinked arsc AND it covers every lib package); fall
        // back to the build's if the relink didn't produce one (e.g. it used the build's compiled-flats base).
        val rJar = linked?.rJar?.takeIf { Files.exists(it) }
            ?: dev.ide.android.support.AndroidBuildSystem.rJarPath(module, variant).takeIf { Files.exists(it) }
        val runtimeLibs = resolved?.dexJars ?: emptyList()
        val moduleOutputs = modules().flatMap { m ->
            runCatching {
                ModuleCompilationContext.create(store.workspace, m, activeConfigs(m)).classpath.entries
                    .filter { it.kind == dev.ide.model.ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }
            }.getOrDefault(emptyList())
        }
        val libs = dev.ide.model.MavenClasspath.dedupeForAndroidDex(
            (runtimeLibs + moduleOutputs).filter { Files.exists(it) && it.toString().endsWith(".jar") }.distinct()
        )

        // Dex-readiness gate: the preview NO LONGER dexes libraries itself (the cold ~30s D8 pass). If any runtime
        // library isn't already in the shared dex cache — a fresh project, or a newly-added dependency — don't
        // render (which would trigger that dexing); return the owned fallback flagged `buildRequired` so the UI
        // prompts a one-time "prepare libraries" build. Once every library is dexed (by that build or a prior
        // one) the check passes and the real render proceeds with only cheap cache copies + the merge.
        val dexCacheRoot = (sharedCachesRoot ?: store.rootPath).resolve("caches").resolve("dex")
        val undexed = runCatching {
            val hashDir = (sharedCachesRoot ?: store.rootPath).resolve("caches").resolve("preview-dexcheck")
            Files.createDirectories(hashDir)
            val universe = dev.ide.android.support.tasks.SharedLibraryDexer
                .computeUniverse(libs, hashDir, facet.minSdk, desugaredLibConfig = null)
            dev.ide.android.support.tasks.SharedLibraryDexer
                .undexedLibraries(libs, universe, dexCacheRoot, facet.minSdk, release = false)
        }.getOrDefault(emptyList())
        if (undexed.isNotEmpty()) {
            _realViewProgress.value = null
            val base = ownedPreview(module, repo, fingerprint, themeName, title, text, request, extraProblem = null)
            return (base ?: dev.ide.preview.LayoutPreviewResult(
                dev.ide.preview.RenderNode().apply { renderer = dev.ide.preview.PlaceholderRenderer; tag = "real-view" },
                dev.ide.preview.impl.ProjectPreviewResources(repo, request.density, request.density, night = request.night, themeName = themeName),
                request.density, request.density,
            )).apply { buildRequired = true; undexedCount = undexed.size }
        }

        val classpath = libs + listOfNotNull(rJar)
        val req = dev.ide.preview.impl.RealViewRequest(
            layoutName = file.fileName.toString().substringBeforeLast('.'),
            layoutText = text,
            widthPx = request.widthPx, heightPx = request.heightPx, density = request.density, night = request.night,
            resourcesAp = resourcesAp, classpath = classpath, packageName = facet.namespace, themeName = themeName,
            minApi = facet.minSdk,
        ).apply {
            // Fine render stages ("Dexing"/"Inflating"/"Drawing") from the runtime drive the status chip.
            stageListener = { _realViewProgress.value = PreviewProgress(it) }
        }
        _realViewProgress.value = PreviewProgress("Rendering")
        val result = runCatching { runtime.render(req) }.getOrElse { t ->
            dev.ide.preview.impl.RealViewResult(null, error = t.message ?: t.javaClass.simpleName)
        }
        // The render is a live native Bitmap on device (no PNG round-trip) or PNG bytes as the portable form.
        val nativeImage = result?.nativeBitmap
        val png = result?.pngBytes
        if (nativeImage == null && png == null) {
            return ownedPreview(module, repo, fingerprint, themeName, title, text, request,
                "Real-view render unavailable (${result?.error ?: "no result"}) — showing owned rendering")
        }
        val resources = dev.ide.preview.impl.ProjectPreviewResources(
            repo, request.density, request.density, night = request.night, themeName = themeName
        )
        val root = dev.ide.preview.RenderNode().apply { renderer = dev.ide.preview.PlaceholderRenderer; tag = "real-view" }
        // Trace each captured view back to its `<Tag …>` in the live buffer (stamping sourceOffset) so the
        // Preview can open the editable attribute editor on tap. Best-effort — a failure leaves the tree
        // unstamped (read-only), never breaking the render.
        val viewTree = result?.viewTree?.let { vt ->
            runCatching { LayoutSourceMapper.stamp(vt, parseLayoutXml(file, text)) }.getOrDefault(vt)
        }
        return dev.ide.preview.LayoutPreviewResult(
            root, resources, request.density, request.density, renderedImage = png, renderedNativeImage = nativeImage,
            viewTree = viewTree,
        )
    }

    /** Parse the live layout buffer into the tolerant XML DOM (with source offsets) for the attribute editor. */
    private fun parseLayoutXml(file: Path, text: String): XmlParsedFile =
        dev.ide.lang.xml.XmlIncrementalParser()
            .parseFull(EditorDocument(store.vfs.fileFor(file), docVersion.incrementAndGet(), text)) as XmlParsedFile

    // ---- Real-view layout attribute editor -----------------------------------------------------------------
    // The Preview's editable attribute panel. It edits the layout XML source (the same file the Code view
    // shows), driven by the SAME metadata + resource index the XML editor's completion uses, so only attributes
    // valid for the tapped view are offered and value completion is byte-for-byte the editor's.

    /**
     * The attribute-editor model for the layout element at [sourceOffset] in [file]'s live buffer [text] — its
     * currently-set attributes (from the source DOM) plus the allowed-but-unset attributes for the view (from
     * the SDK metadata, hierarchy-aware, incl. custom-view `app:` attrs). Null when [file] isn't an editable
     * layout element.
     */
    fun layoutElement(file: Path, text: String, sourceOffset: Int, id: String?): LayoutElementInfo? {
        val module = moduleForResourceFile(file) ?: return null
        val contributor = androidXmlContributor(module) ?: return null
        val tag = resolveElement(parseLayoutXml(file, text), sourceOffset, id) ?: return null
        val parentTag = (tag.parent as? XmlNode)?.takeIf { it.kind == XmlNodeKinds.TAG }?.name
        val allowed = contributor.allowedAttributes(tag.name, parentTag)
        val bySpec = allowed.associateBy { it.name }
        val setAttrs = tag.attributes.mapNotNull { a ->
            val name = a.name ?: return@mapNotNull null
            if (name == "xmlns" || name.startsWith("xmlns:")) return@mapNotNull null
            val spec = bySpec[name]
            LayoutAttrInfo(
                name = name,
                value = a.valueNode?.text()?.toString() ?: "",
                boolean = spec?.boolean ?: false,
                enumValues = spec?.enumValues ?: emptyList(),
                flagValues = spec?.flags ?: emptyList(),
                resourceRClasses = spec?.resourceTypes?.map { it.rClass } ?: emptyList(),
            )
        }
        val present = setAttrs.mapTo(HashSet()) { it.name }
        val addable = allowed.filter { it.name !in present }.map {
            LayoutAttrInfo(it.name, null, it.boolean, it.enumValues, it.flags, it.resourceTypes.map { r -> r.rClass })
        }
        return LayoutElementInfo(tag.name ?: "", elementId(tag), tag.startOffset, setAttrs, addable)
    }

    /**
     * Value completion for [attrName] on the layout element at [sourceOffset], as if [fieldText] (caret at
     * [caret]) were typed into the attribute's value — the exact candidates the XML editor offers, since it runs
     * the same [XmlContextScanner] + [AndroidXmlContributor] over a synthetic buffer that carries the in-progress
     * value. Returned replacement range is field-relative (so the caller can splice into its text field).
     */
    fun completeLayoutAttributeValue(
        file: Path, text: String, sourceOffset: Int, id: String?, attrName: String, fieldText: String, caret: Int
    ): CompletionResult {
        val empty = CompletionResult(emptyList(), false, TextRange(0, 0))
        val module = moduleForResourceFile(file) ?: return empty
        val analyzer = analyzerFor(module, languageFor(file)) as? XmlSourceAnalyzer ?: return empty
        val tag = resolveElement(parseLayoutXml(file, text), sourceOffset, id) ?: return empty
        val (synthText, valueStart) = spliceAttributeValue(text, tag, attrName, fieldText) ?: return empty
        val synthCaret = valueStart + caret.coerceIn(0, fieldText.length)
        val parsed = parseLayoutXml(file, synthText)
        val pos = XmlContextScanner.scan(synthText, synthCaret, parsed, file.toString())
        val items = analyzer.contributors
            .flatMap { runCatching { it.contribute(pos) }.getOrDefault(emptyList()) }
            .filter { XmlCompletion.nameMatches(it.label, pos.prefix) || XmlCompletion.nameMatches(it.insertText, pos.prefix) }
            .sortedWith(compareBy({ it.sortPriority }, { it.label.lowercase() }))
        val relStart = (pos.replacementRange.start - valueStart).coerceIn(0, fieldText.length)
        val relEnd = (pos.replacementRange.end - valueStart).coerceIn(relStart, fieldText.length)
        return CompletionResult(items, false, TextRange(relStart, relEnd))
    }

    /** Text edits that set [attrName]="[value]" on the element at [sourceOffset] (replacing the value if it is
     *  already present, else inserting the attribute + auto-declaring its `xmlns` when needed). */
    fun setLayoutAttributeEdits(file: Path, text: String, sourceOffset: Int, id: String?, attrName: String, value: String): List<TextEdit> {
        val parsed = parseLayoutXml(file, text)
        val tag = resolveElement(parsed, sourceOffset, id) ?: return emptyList()
        val escaped = escapeXmlAttrValue(value)
        val existing = tag.attributes.firstOrNull { it.name == attrName }
        if (existing != null) {
            val vn = existing.valueNode
            // No-op guard: setting an attribute to the value it already has produces NO edit, so a redundant
            // commit (e.g. a control re-emitting its current value) can't churn the buffer or wedge the editor.
            if (vn != null && vn.text().toString() == value) return emptyList()
            return if (vn != null) listOf(TextEdit(TextRange(vn.startOffset, vn.endOffset), escaped))
            else listOf(TextEdit(TextRange(existing.startOffset, existing.endOffset), "$attrName=\"$escaped\""))
        }
        val edits = ArrayList<TextEdit>()
        val at = attributeInsertOffset(tag)
        edits.add(TextEdit(TextRange(at, at), " $attrName=\"$escaped\""))
        namespaceDeclarationEdit(attrName, parsed)?.let { edits.add(it) }
        return edits
    }

    /** Text edits that remove [attrName] from the element at [sourceOffset] (with one leading whitespace). */
    fun removeLayoutAttributeEdits(file: Path, text: String, sourceOffset: Int, id: String?, attrName: String): List<TextEdit> {
        val tag = resolveElement(parseLayoutXml(file, text), sourceOffset, id) ?: return emptyList()
        val existing = tag.attributes.firstOrNull { it.name == attrName } ?: return emptyList()
        var start = existing.startOffset
        if (start > 0 && text[start - 1].isWhitespace()) start--
        return listOf(TextEdit(TextRange(start, existing.endOffset), ""))
    }

    /** The module's Android XML completion contributor (allowed attributes + value candidates), or null. */
    private fun androidXmlContributor(module: Module): AndroidXmlContributor? =
        (analyzerFor(module, LanguageId("xml")) as? XmlSourceAnalyzer)?.contributors
            ?.filterIsInstance<AndroidXmlContributor>()?.firstOrNull()

    /**
     * The element the editor is anchored to in the freshly-parsed [parsed] — located by [id] when the view has
     * one (robust: the raw [sourceOffset] from the captured tree can lag the live buffer after an edit that
     * shifts offsets, e.g. an auto-declared root `xmlns`), else the enclosing tag at [sourceOffset].
     */
    private fun resolveElement(parsed: XmlParsedFile, sourceOffset: Int, id: String?): XmlNode? {
        if (id != null) rootTagOf(parsed)?.let { findById(it, id) }?.let { return it }
        return enclosingTag(parsed, sourceOffset)
    }

    /** The element in the [tag] subtree whose `@+id/…`/`@id/…` entry name is [id], or null. */
    private fun findById(tag: XmlNode, id: String): XmlNode? {
        val here = tag.attributes.firstOrNull { it.name == "android:id" }?.valueNode?.text()?.toString()
            ?.substringAfterLast('/')
        if (here == id) return tag
        for (child in tag.childTags) findById(child, id)?.let { return it }
        return null
    }

    /** The nearest enclosing element (TAG) at [offset], or null. */
    private fun enclosingTag(parsed: XmlParsedFile, offset: Int): XmlNode? {
        var node: dev.ide.lang.dom.DomNode? = parsed.nodeAt(offset)
        while (node != null && !(node is XmlNode && node.kind == XmlNodeKinds.TAG)) node = node.parent
        return node as? XmlNode
    }

    /** The `@+id/…`/`@id/…` entry name declared on [tag], or null. */
    private fun elementId(tag: XmlNode): String? =
        tag.attributes.firstOrNull { it.name == "android:id" }?.valueNode?.text()?.toString()
            ?.substringAfterLast('/')?.ifEmpty { null }

    /** Where a new attribute is spliced into [tag]'s start tag — after the last attribute, else after the
     *  tag name (always before the closing `>`/`/>`). */
    private fun attributeInsertOffset(tag: XmlNode): Int {
        tag.attributes.maxByOrNull { it.endOffset }?.let { return it.endOffset }
        return tag.startOffset + 1 + (tag.name?.length ?: 0)
    }

    /** Splice [fieldText] into [attrName]'s value on [tag] in a synthetic copy of [text] (adding the attribute
     *  when absent), returning the new text + the offset where the value begins. Null when it can't be placed. */
    private fun spliceAttributeValue(text: String, tag: XmlNode, attrName: String, fieldText: String): Pair<String, Int>? {
        val vn = tag.attributes.firstOrNull { it.name == attrName }?.valueNode
        if (vn != null) {
            return (text.substring(0, vn.startOffset) + fieldText + text.substring(vn.endOffset)) to vn.startOffset
        }
        val at = attributeInsertOffset(tag)
        val prefix = " $attrName=\""
        return (text.substring(0, at) + prefix + fieldText + "\"" + text.substring(at)) to (at + prefix.length)
    }

    /** The edit declaring [attrName]'s namespace on the root when it's a known prefix that isn't declared. */
    private fun namespaceDeclarationEdit(attrName: String, parsed: XmlParsedFile): TextEdit? {
        val prefix = attrName.substringBefore(':', "")
        val uri = AndroidXmlContributor.NAMESPACE_URIS[prefix] ?: return null
        val root = rootTagOf(parsed) ?: return null
        val declared = root.attributes.mapNotNull { it.name }.filter { it.startsWith("xmlns:") }
            .mapTo(HashSet()) { it.removePrefix("xmlns:") }
        if (prefix in declared) return null
        val at = root.startOffset + 1 + (root.name?.length ?: 0)
        return TextEdit(TextRange(at, at), " xmlns:$prefix=\"$uri\"")
    }

    private fun rootTagOf(parsed: XmlParsedFile): XmlNode? {
        fun find(node: dev.ide.lang.dom.DomNode): XmlNode? {
            if (node is XmlNode && node.kind == XmlNodeKinds.TAG) return node
            for (child in node.children) find(child)?.let { return it }
            return null
        }
        return find(parsed)
    }

    private fun escapeXmlAttrValue(v: String): String =
        v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** Fully-qualified view tags in a layout (`<lower.pkg.Upper …>`) — candidate custom views. */
    private val customViewTagRegex = Regex("""<([a-z][\w.]*\.[A-Z]\w*)[\s/>]""")

    /**
     * Does [xml] reference one of the USER's OWN custom views — a fully-qualified tag whose class has a `.java`/
     * `.kt` source somewhere in the project? Only those need the compile+instrument+dex pipeline (it exists to
     * run a user view's own drawing code). Library/framework views (`androidx.*`, Material) are also FQ tags but
     * carry no project source: they render via a registered renderer or a placeholder and must NOT drag the
     * whole project through a preview build — which is wasteful and, with unresolved deps, fails outright.
     */
    private fun referencesCustomView(xml: String): Boolean {
        val fqns = customViewTagRegex.findAll(xml).map { it.groupValues[1] }.toSet()
        if (fqns.isEmpty()) return false
        val roots = modules().flatMap { sourceRoots(it) }.filter { Files.isDirectory(it) }
        return fqns.any { fqn ->
            val rel = fqn.replace('.', '/')
            roots.any { Files.exists(it.resolve("$rel.java")) || Files.exists(it.resolve("$rel.kt")) }
        }
    }

    // ---- Preview caches: layout preview re-renders on every keystroke, so the per-render work below
    // (parse all res XML into a ResourceRepository; preview-compile + instrument + dex the project's Java
    // closure for custom views) is memoized. Both depend on the project's resource/source FILES, never on
    // the layout buffer being typed, so a content fingerprint over those files is the cache key — typing in
    // a layout hits the cache; saving a res or .java file changes the fingerprint and rebuilds.

    private class CachedRepo(
        val fingerprint: String, val repo: ResourceRepository
    )

    private val repoCache = ConcurrentHashMap<String, CachedRepo>()

    /** Serializes resource-repository builds so concurrent first-callers (analysis + preview on project open)
     *  don't each launch a full parse of the same dependency `res/` set. */
    private val repoBuildLock = Any()

    private class CachedCustomViews(
        val fingerprint: String, val factory: dev.ide.preview.impl.CustomViewFactory?
    )

    private val customViewCache = ConcurrentHashMap<String, CachedCustomViews>()

    /**
     * The module's merged [dev.ide.android.support.resources.ResourceRepository] — the ONE shared,
     * fingerprint-cached instance every consumer (synthetic `R`, layout preview, value hints, go-to-def,
     * color resolution) uses, so a Material/AndroidX resource set is parsed once per content-state, not
     * re-parsed per use case. Rebuilt only when the module's res files change (the `.xml` fingerprint), and
     * stampede-guarded so concurrent first-callers don't each launch a full parse.
     */
    private fun resourceRepository(module: Module): CachedRepo? {
        val key = module.id.value
        val fp = filesFingerprint(runCatching {
            AndroidResources.resourceDirs(
                module, store.workspace
            )
        }.getOrDefault(emptyList()), ".xml")
        repoCache[key]?.let { if (it.fingerprint == fp) return it }
        return synchronized(repoBuildLock) {
            val cur = repoCache[key]
            if (cur != null && cur.fingerprint == fp) cur
            else runCatching { AndroidResources.repository(module, store.workspace) }.getOrNull()
                ?.let { CachedRepo(fp, it).also { c -> repoCache[key] = c } }
        }
    }

    /** The shared merged repository instance for [module] (see [resourceRepository]), or null. */
    private fun resourceRepo(module: Module): ResourceRepository? =
        resourceRepository(module)?.repo

    /**
     * Cached [buildCustomViewFactory]. Keyed on a fingerprint of every module's Java sources + [repoFingerprint]
     * (the styled-attr resolver and the synthetic `R` the classes compile against derive from resources) — so
     * editing the layout XML reuses the compiled+dexed factory, and only a source/resource change recompiles.
     */
    private fun cachedCustomViewFactory(
        module: Module,
        repo: ResourceRepository,
        repoFingerprint: String
    ): dev.ide.preview.impl.CustomViewFactory? {
        val sourceFp = filesFingerprint(modules().flatMap { sourceRoots(it) }, ".java")
        val fp = "$sourceFp|$repoFingerprint"
        customViewCache[module.id.value]?.let { if (it.fingerprint == fp) return it.factory }
        return buildCustomViewFactory(module, repo).also {
            customViewCache[module.id.value] = CachedCustomViews(fp, it)
        }
    }

    /** Stable content fingerprint over the files (with [suffix]) under [roots]: sorted `path:size:mtime` tuples. */
    private fun filesFingerprint(roots: List<Path>, suffix: String): String {
        val entries = ArrayList<String>()
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            runCatching {
                Files.walk(root).use { w ->
                    w.filter { it.toString().endsWith(suffix) && Files.isRegularFile(it) }
                        .forEach { p ->
                            runCatching {
                                entries.add(
                                    "$p:${Files.size(p)}:${
                                        Files.getLastModifiedTime(
                                            p
                                        ).toMillis()
                                    }"
                                )
                            }
                        }
                }
            }
        }
        entries.sort()
        return entries.joinToString("\n").hashCode().toString()
    }

    /**
     * Preview-compile the module's Java sources + synthetic `R`/`BuildConfig` against `android.jar`, instrument
     * each class with [dev.ide.preview.impl.BridgeRemapper], and hand the result to the platform
     * [CustomViewRuntime] to produce a live custom-view factory. Null when there's no
     * runtime, no `android.jar`, or compilation fails (the inflater then shows placeholders).
     */
    private fun buildCustomViewFactory(
        module: Module, repo: ResourceRepository
    ): dev.ide.preview.impl.CustomViewFactory {
        val runtime = customViewRuntime
            ?: return failingCustomViewFactory("no custom-view preview runtime on this platform")
        val androidJar = previewAndroidJar()
            ?: return failingCustomViewFactory("no android.jar available for the preview compile")
        return runCatching {
            val work = store.rootPath.resolve(".platform/caches/preview/${module.id.value}")
            val srcDir = work.resolve("src")
            val outDir = work.resolve("classes")
            outDir.toFile()
                .deleteRecursively(); Files.createDirectories(srcDir); Files.createDirectories(
            outDir
        )

            // Synthetic R/BuildConfig as compilable Java so R.styleable.* etc. resolve at preview-compile time.
            val sources = ArrayList<Path>()
            for ((fqn, src) in syntheticOverlay()) {
                val f = srcDir.resolve(fqn.replace('.', '/') + ".java")
                Files.createDirectories(f.parent); Files.write(
                    f, String(src).toByteArray()
                ); sources.add(f)
            }
            // Compile the whole project's Java closure together so cross-module refs (the custom view's
            // siblings/deps) resolve; library jars come from each module's analysis classpath.
            modules().forEach { m ->
                sourceRoots(m).forEach { root ->
                    if (Files.isDirectory(root)) Files.walk(root).use { w ->
                        w.filter { it.toString().endsWith(".java") }.forEach { sources.add(it) }
                    }
                }
            }
            // Dedupe to one jar per artifact before dexing: flattening every module's classpath can present the
            // same library at two versions, and the bundled kotlin-stdlib (a `.platform/…` path) collides with a
            // Maven kotlin-stdlib the project resolves — either makes D8 fail with "Type … is defined multiple
            // times". (KMP `-android`/`-jvm` no longer collide here: the resolver selects one variant up front.)
            val deps = dev.ide.model.MavenClasspath.dedupeForAndroidDex(
                modules().flatMap { m ->
                    runCatching {
                        ModuleCompilationContext.create(
                            store.workspace, m, activeConfigs(m)
                        ).classpath.entries.map { Paths.get(it.root.path) }
                    }.getOrDefault(emptyList())
                }.filter { Files.exists(it) }.distinct()
            )
            // Java 8, NOT a fixed 17: the preview always feeds android.jar as -bootclasspath, and ecj rejects
            // -bootclasspath at compliance >= 9 ("option -bootclasspath not supported at compliance level 9 and
            // above"). The on-device build is JAVA_8 for the same reason (android.jar is the boot library), and
            // android.jar's signatures resolve at any source level, so 8 is the safe cap for the preview compile.
            // Boot = android.jar PLUS the desugar stubs (`core-lambda-stubs.jar`: `java.lang.invoke.Lambda-
            // Metafactory`/`StringConcatFactory`), which android.jar omits — without them any lambda or string
            // concat in the compiled closure fails ecj with "The type java.lang.invoke.LambdaMetafactory cannot
            // be resolved". Mirrors [compileBootClasspath] (the analyzer/build use the same boot library).
            val result = JdtBatchCompiler.compile(
                sources, deps, outDir, sourceLevel = "8",
                bootClasspath = listOf(androidJar) + (androidTools?.desugarStubs ?: emptyList()),
            )
            if (!result.success) {
                return@runCatching failingCustomViewFactory("preview compile failed:\n${formatCompileErrors(result)}")
            }

            // Instrument every compiled .class in place (reparent View bases, redirect obtainStyledAttributes).
            val remapper = dev.ide.preview.impl.bridge.BridgeRemapper()
            Files.walk(outDir).use { w ->
                w.filter { it.toString().endsWith(".class") }.forEach { p ->
                    runCatching { Files.write(p, remapper.transform(Files.readAllBytes(p))) }
                }
            }

            val ids = dev.ide.android.support.resources.RIdAssignment(repo)
            val resolver = dev.ide.preview.impl.ProjectPreviewResources(repo)
            val styled = dev.ide.preview.impl.StyledAttrResolver { rawAttrs, styleableIds ->
                styleableIds.map { id ->
                    val (type, name) = ids.nameOf(id) ?: return@map null
                    if (type != ResourceType.ATTR) return@map null
                    val raw = rawAttrs[name] ?: return@map null
                    resolver.resolve(raw, attrFormatOf(repo, name))
                }
            }
            runtime.createFactory(outDir, deps, styled)
                ?: failingCustomViewFactory("the custom-view runtime returned no factory")
        }.getOrElse { t ->
            // Surface the reason (incl. a CustomViewPreviewException thrown by createFactory, e.g. a dex
            // failure) through a factory that fails every create() with it, rather than a silent null →
            // generic "no preview runtime" message in the preview pane.
            failingCustomViewFactory(
                t.message ?: "preview setup failed (${t.javaClass.simpleName})"
            )
        }
    }

    /**
     * Render a failed preview compile as a short, copy-friendly block: each error as `File.java:line  message`
     * (file name only — the absolute cache path is noise) with the actual ecj description, capped at a few lines.
     */
    private fun formatCompileErrors(result: JdtBatchCompiler.Result): String {
        val errs = result.diagnostics.filter { it.isError }
        if (errs.isNotEmpty()) {
            val cap = 5
            val shown = errs.take(cap).joinToString("\n") { d ->
                val name = d.file?.substringAfterLast('/')?.substringAfterLast('\\')
                val loc = name?.let { n -> d.line?.let { "$n:$it" } ?: n }
                if (loc != null) "$loc  ${d.message}" else d.message
            }
            return if (errs.size > cap) "$shown\n…and ${errs.size - cap} more" else shown
        }
        // Fallback when nothing parsed (an unusual compiler message shape): the raw lines, sans the path noise.
        return result.messages.take(3).joinToString("\n").ifBlank { "(no diagnostics)" }
    }

    /** A [CustomViewFactory] whose every [create] fails with [reason], so the preview pane shows it. */
    private fun failingCustomViewFactory(reason: String): dev.ide.preview.impl.CustomViewFactory =
        object : dev.ide.preview.impl.CustomViewFactory {
            override fun create(
                fqName: String,
                attrs: dev.ide.preview.AttrReader,
                ctx: dev.ide.preview.RenderContext
            ): dev.ide.preview.RenderNode =
                throw dev.ide.preview.impl.CustomViewPreviewException(reason)
        }

    /** Map a project attr's declared `format` (or an inference) to a preview [dev.ide.preview.ValueFormat]. */
    private fun attrFormatOf(
        repo: ResourceRepository, name: String
    ): dev.ide.preview.ValueFormat = when (repo.attrFormat(name)) {
        "color" -> dev.ide.preview.ValueFormat.COLOR
        "dimension" -> dev.ide.preview.ValueFormat.DIMENSION
        "integer" -> dev.ide.preview.ValueFormat.INTEGER
        "boolean" -> dev.ide.preview.ValueFormat.BOOLEAN
        "float", "fraction" -> dev.ide.preview.ValueFormat.FLOAT
        "string" -> dev.ide.preview.ValueFormat.STRING
        "reference" -> dev.ide.preview.ValueFormat.REFERENCE
        else -> dev.ide.preview.ValueFormat.ANY
    }

    /** The `android.jar` for the preview compile — the device's bundled jar, else a detected desktop SDK's. */
    private fun previewAndroidJar(): Path? =
        androidTools?.androidJar ?: (AndroidSdk.findSdkRoot()
            ?.let { AndroidSdk.detect(it) }?.androidJar)

    /** The activity/application theme name (raw dotted, sans `@style/`) and window title from the manifest. */
    private fun manifestThemeAndLabel(
        module: Module, repo: ResourceRepository
    ): Pair<String?, String> {
        val manifest = manifestPath(module)?.let { runCatching { it.readText() }.getOrNull() } ?: ""
        val themeName =
            Regex("""android:theme\s*=\s*"@style/([^"]+)"""").find(manifest)?.groupValues?.get(1)
        val labelRaw =
            Regex("""android:label\s*=\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
        val title = when {
            labelRaw == null -> module.name
            labelRaw.startsWith("@string/") -> repo.definitions(
                ResourceType.STRING, sanitizeResName(labelRaw.removePrefix("@string/"))
            ).firstOrNull()?.value ?: module.name

            else -> labelRaw
        }
        return themeName to title
    }

    /** A [DrawableResolver] backed by [module]'s merged resource repository (colors/dimens/nested drawables). */
    private fun drawableResolver(module: Module): DrawableResolver {
        val repo = resourceRepo(module) ?: return DrawableResolver.NONE
        return object : DrawableResolver {
            override fun resolveColor(ref: String): Long? = resolveColorRef(ref, repo, 0)

            override fun resolveDimenDp(ref: String): Float? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                val v =
                    repo.definitions(ResourceType.DIMEN, name).firstOrNull()?.value ?: return null
                return DIMEN_LITERAL.find(v)?.groupValues?.get(1)?.toFloatOrNull()
            }

            override fun resolveDrawable(ref: String): ResolvedDrawable? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                // A @color used where a drawable is expected resolves to a flat fill — let the color path handle it.
                if (ref.contains("color") && repo.has(ResourceType.COLOR, name)) return null
                val item =
                    repo.definitions(ResourceType.DRAWABLE, name).firstOrNull() ?: repo.definitions(
                        ResourceType.MIPMAP, name
                    ).firstOrNull() ?: return null
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

    /**
     * Resource candidates (name + value hint) of [type] for completion, **straight from the incremental
     * resource index** - ONE `prefix("type/")` query yields both the name and the resolved value, so the
     * completion popup never parses or fingerprints a `ResourceRepository`. No repository fallback: until the
     * index has built, resource completion is simply empty (the "dumb until indexed" rule). Ids declared inline
     * with `@+id/…` in open buffers (not yet saved/indexed) are surfaced live so `@id/…` completes immediately.
     */
    private fun resourceCandidatesFor(module: Module, type: ResourceType): List<ResourceCandidate> {
        val byName = LinkedHashMap<String, ResourceCandidate>()
        for (hit in indexService.prefix<ResourceDeclValue>(
            AndroidResourceIndex.id, "${type.rClass}/", limit = 2000
        )) {
            byName.putIfAbsent(hit.value.name, ResourceCandidate(hit.value.name, hit.value.value))
        }
        if (type == ResourceType.ID) for (id in liveDeclaredIds()) byName.putIfAbsent(
            id, ResourceCandidate(id)
        )
        return byName.values.toList()
    }

    private val ID_DECL = Regex("""@\+id/([A-Za-z_][\w.]*)""")

    /** `@+id/…` declarations across all open editor buffers (sanitized), for live id completion. */
    private fun liveDeclaredIds(): List<String> = openDocuments.values.flatMap { text ->
        ID_DECL.findAll(text).map { sanitizeResName(it.groupValues[1]) }
    }.distinct()

    /** Framework (`@android:`) resource names by android.jar identity - scanned once from `android.R$*`. */
    private val frameworkResCache = ConcurrentHashMap<String, Map<ResourceType, List<String>>>()

    /**
     * Framework resource names of [type] for `@android:type/name` completion, scanned from the SDK/device
     * `android.jar`'s `android.R$*` classes and cached by the jar's identity (it's fixed per platform). Empty
     * when there's no android.jar (no Android SDK / a non-Android module).
     */
    private fun frameworkResources(type: ResourceType): List<String> {
        val jar = previewAndroidJar() ?: return emptyList()
        val key = runCatching {
            "$jar:${Files.size(jar)}:${
                Files.getLastModifiedTime(jar).toMillis()
            }"
        }.getOrDefault(jar.toString())
        return frameworkResCache.getOrPut(key) {
            runCatching { dev.ide.android.support.resources.FrameworkResourceScanner.scan(jar) }.getOrDefault(
                emptyMap()
            )
        }[type].orEmpty()
    }

    /** Whether the index knows resource `@type/name` (precise). */
    private fun indexHasResource(type: ResourceType, name: String): Boolean =
        indexService.exact<ResourceDeclValue>(
            AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)
        ).any()

    /** The resolved literal value of `@type/name` from the index (a value resource's text/`#…`/`16dp`), or
     *  null for a file resource / unindexed reference. Used for the inlay-hint preview. */
    private fun resourceHintValue(type: ResourceType, name: String): String? =
        indexService.exact<ResourceDeclValue>(
            AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)
        ).firstNotNullOfOrNull { it.value }

    /** Whether the index has any resource of [type]; used to stay conservative (don't flag invisible types). */
    private fun indexTypeHasAny(type: ResourceType): Boolean =
        indexService.prefix<ResourceDeclValue>(
            AndroidResourceIndex.id, "${type.rClass}/", limit = 1
        ).any()

    private fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()

    private fun resourceRoots(m: Module): List<Path> =
        m.sourceSets.flatMap { it.contentRoots }.filter { ContentRole.ANDROID_RES in it.roles }
            .map { Paths.get(it.dir.path) }

    /** The Android module whose `res/` tree contains [file] (an XML resource), or null. */
    private fun moduleForResourceFile(file: Path): Module? {
        val target = file.toAbsolutePath().normalize()
        return modules().firstOrNull { m ->
            m.facets.get(AndroidFacet.KEY) != null && resourceRoots(m).any {
                target.startsWith(
                    it.toAbsolutePath().normalize()
                )
            }
        }
    }

    /** Builds each per-file analysis target off the live overlay (true working copies, as completion does). */
    private inner class IdeAnalysisEnvironment : AnalysisEnvironment {
        override suspend fun targetFor(file: VirtualFile, needsBindings: Boolean): AnalysisTarget? {
            val path = Paths.get(file.path)
            // `moduleForEditableFile` (not `moduleForFile`) so XML resource files + the manifest, which sit
            // outside the source roots, still resolve to a module and get analyzed.
            val module = moduleForEditableFile(path) ?: return null
            val key = path.toAbsolutePath().normalize()
            val text =
                openDocuments[key] ?: runCatching { key.readText() }.getOrNull() ?: return null
            // Pick the analyzer by the file's language (Java / Kotlin / XML); each backend's diagnostic +
            // action providers are language-gated, so the one pipeline serves every language.
            val analyzer = analyzerFor(module, languageFor(path))
            // Tier gate: a SEMANTIC+ pass gets the binding-resolved tree (so analyzers can resolve types/
            // symbols and the one pass also yields the compiler diagnostics); a SYNTAX-only pass gets the
            // cheap syntax tree (no classpath scan, no shadow-file move) the incremental parser produces.
            // Binding resolution is a JDT-only path; Kotlin/XML always take the incremental parse.
            val doc = EditorDocument(file, docVersion.incrementAndGet(), text)
            val parsed =
                if (needsBindings && analyzer is JdtSourceAnalyzer) analyzer.parse(file, text)
                else analyzer.incrementalParser.parseFull(doc)
            return IdeAnalysisTarget(
                file, parsed, parsed.documentVersion, analyzer, indexService, module
            )
        }

        override fun languageOf(file: VirtualFile): LanguageId = languageFor(Paths.get(file.path))

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
                runCatching {
                    Files.walk(root).use { s ->
                        s.filter { it.toString().endsWith(".java") }
                            .forEach { out += store.vfs.fileFor(it) }
                    }
                }
            }
            return out.asSequence()
        }

        override suspend fun targetFor(file: VirtualFile): AnalysisTarget =
        // Batch/project sweep: hand analyzers a binding-resolved tree (correctness over the per-keystroke
            // cost, since this path is not the typing path).
            analysisEnvironment.targetFor(file, needsBindings = true)
                ?: error("no analysis target for ${file.path}")

        override fun checkCanceled() {}
    }

    override fun close() {
        // Stop being the app's active engine (only if a newer project hasn't already taken over).
        if (env.activeEngine === this) env.activeEngine = null
        analysisEngine.dispose()
        // The build/run cleanup (unblocking an in-flight run + clearing the process-global sandbox broker)
        // now happens in BuildService.dispose(), run when the workspace container is disposed by store.close().
        indexScope.cancel()
        // The SDK manager is APPLICATION-scoped (shared across projects), so this engine only drops its own
        // change subscription — the shared instance itself is disposed by the application container at app
        // shutdown, not on a per-project close.
        runCatching { sdkChangeSubscription?.dispose() }
        // Unsubscribe the event hub from the (app-wide) bus so a closed engine's reactions never fire again.
        runCatching { events.close() }
        // Dispose the workspace + module service containers (the application container, the parent, is owned
        // by ProjectManager). This disposes the per-module analyzers, closing their library-jar handles,
        // before the index's segment channels are released.
        runCatching { store.close() }
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
        /** Cache subdirs under `.platform/caches` the "Clear caches" action removes — all regenerable, none
         *  shared across projects (the static SDK/library index segments live under the shared caches root). */
        private val CLEARABLE_CACHE_DIRS =
            listOf("kotlin-ext", "custom-views", "preview", "preview-libs", "build", "source-index")


        /** Free-heap floor (on device) below which the Kotlin parser-env warm-up is skipped at project open; it
         *  then stands up lazily on the first Kotlin file instead. Keeps cold start from tipping a tight device
         *  into the low-memory killer. */
        private const val PARSER_WARMUP_MIN_FREE_BYTES = 96L * 1024 * 1024

        /** Free-heap floor (on device) below which the heavier Kotlin COMPILE warm-up — a throwaway compile that
         *  loads and RETAINS the frontend + JVM backend — is skipped; the first real build pays its ~1s cold
         *  start instead. Higher than [PARSER_WARMUP_MIN_FREE_BYTES] because the compile path costs far more. */
        private const val COMPILER_WARMUP_MIN_FREE_BYTES = 224L * 1024 * 1024


        /** Java reserved words + literals, rejected as rename targets (a valid identifier can't be one). */
        private val JAVA_RESERVED = setOf(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "var",
            "record",
            "yield",
            "sealed",
            "permits",
        )

        /** Declaration kinds that name a top-level type (for the import sweep on a package move). */
        private val TOP_LEVEL_TYPE_KINDS = setOf(
            JavaSourceIndexer.DeclKind.CLASS, JavaSourceIndexer.DeclKind.INTERFACE,
            JavaSourceIndexer.DeclKind.ENUM, JavaSourceIndexer.DeclKind.RECORD,
            JavaSourceIndexer.DeclKind.ANNOTATION,
        )

        /**
         * Test fixture: create the Android multi-module sample (`app → feature → core`) at [root] (replacing
         * any existing one) and open it. On a desktop the framework `android.jar` is resolved from an installed
         * Android SDK so `android.*` resolves; with no SDK it falls back to a JDK (Java resolves, `android.*`
         * does not). Not used by the launchers — the IDE no longer seeds a demo on first run.
         */
        fun bootstrapDemo(root: Path, sharedCachesRoot: Path? = null): IdeServices {
            if (Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val env = ApplicationEnvironment()
            val (platform, store) = openStore(root, env)
            ensureSdks(store, detectAndroidSdk() ?: JdkSdkProvider.detect(), sharedCachesRoot ?: root)
            val types = ModuleTypeRegistry(platform.extensions)
            SampleAndroidProject.generate(
                store,
                types.resolve("android-app"),
                types.resolve("android-lib"),
                types.resolve("java-lib"),
            )
            store.save()
            return IdeServices(platform, store, sharedCachesRoot = sharedCachesRoot, env = env)
        }

        /**
         * Test fixture: seed the Android sample to [root] on disk **without** opening an engine, so a test can
         * exercise the lazy-open path (list it in the picker, then open it via [ProjectManager.open]). No-op when
         * a project already exists at [root]. The transient platform used to write the model is disposed before
         * returning, so this leaks nothing.
         */
        fun seedDemo(root: Path) {
            if (ModelPersistence.exists(root)) return
            Files.createDirectories(root)
            val env = ApplicationEnvironment()
            val (platform, store) = openStore(root, env)
            try {
                ensureSdks(store, detectAndroidSdk() ?: JdkSdkProvider.detect(), root)
                val types = ModuleTypeRegistry(platform.extensions)
                SampleAndroidProject.generate(
                    store,
                    types.resolve("android-app"),
                    types.resolve("android-lib"),
                    types.resolve("java-lib"),
                )
                store.save()
            } finally {
                platform.dispose()
                env.close()
            }
        }

        /** The plain-Java multi-module demo (`app → util → core`) — the fixture for the Java build/run/completion tests. */
        fun bootstrapJavaDemo(root: Path): IdeServices {
            if (Files.exists(root)) root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val env = ApplicationEnvironment()
            val (platform, store) = openStore(root, env)
            store.replaceSdks(listOf(JdkSdkProvider.detect()))
            SampleProject.generate(
                store, ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            )
            store.save()
            return IdeServices(platform, store, env = env)
        }

        /** An installed Android SDK's `android.jar` as the workspace SDK, or null if none is found. */
        private fun detectAndroidSdk(): SdkData? {
            val sdk = AndroidSdk.findSdkRoot()?.let(AndroidSdk::detect) ?: return null
            // android.jar omits java.lang.invoke.StringConcatFactory / LambdaMetafactory; ship the build-tools
            // desugar stubs alongside it on the boot classpath so Java ≥ 9 string-concat (`"a" + b`) and
            // lambdas resolve during analysis (and compilation). Compile-only — see AndroidSdk.coreLambdaStubs.
            val boot =
                listOf(sdk.androidJar.toString()) + listOfNotNull(sdk.coreLambdaStubs.takeIf {
                    Files.exists(it)
                }?.toString())
            return SdkData("android", boot, sdk.buildToolsDir.toString(), kind = PlatformKind.ANDROID)
        }

        /**
         * Install the workspace SDK table so BOTH platforms are present: the injected [primary] (the Android
         * SDK on-device / android-or-JDK on desktop) AND a JVM "core-Java" SDK, so a plain Java/Kotlin module
         * resolves a non-Android platform ([SdkResolution]) instead of the one android.jar. Idempotent and
         * migration-safe: it also augments an OLDER workspace whose sdks.json holds only the Android SDK
         * (adds the missing core-Java SDK on reopen). [baseDir] roots the filtered-jar cache on-device.
         */
        private fun ensureSdks(store: ProjectModelStore, primary: SdkData?, baseDir: Path) {
            val current = store.data.sdks.toMutableList()
            if (current.isEmpty()) primary?.let { current.add(it) }
            if (current.none { it.kind == PlatformKind.JVM }) {
                val androidLike = current.firstOrNull { it.kind == PlatformKind.ANDROID } ?: primary
                coreJavaSdkFor(androidLike, baseDir)?.let { core ->
                    if (current.none { it.name == core.name }) current.add(core)
                }
            }
            if (current != store.data.sdks) store.replaceSdks(current)
        }

        /**
         * The JVM "core-Java" platform SDK. On the desktop that is the real host JDK; on ART (no JDK) it is
         * android.jar filtered down to the standard `java.*`/`javax.*` surface ([CorePlatformProvider]), the
         * desugar stubs carried over from [primary]. Null when neither is available.
         */
        private fun coreJavaSdkFor(primary: SdkData?, baseDir: Path): SdkData? {
            val host = JdkSdkProvider.detect()
            if (host.name != "synthetic") return host.copy(name = CorePlatformProvider.SDK_NAME, kind = PlatformKind.JVM)
            if (primary == null || primary.kind != PlatformKind.ANDROID) return null
            val androidJar = primary.bootClasspath.firstOrNull()?.let { Paths.get(it) } ?: return null
            val stubs = primary.bootClasspath.drop(1).map { Paths.get(it) }
            return CorePlatformProvider.coreJavaSdk(androidJar, stubs, baseDir.resolve(".platform/caches/core-platform"))
        }

        /** Open an existing workspace at [root] (used by the "Open Project…" picker). */
        fun open(root: Path): IdeServices {
            val env = ApplicationEnvironment()
            val (platform, store) = openStore(root, env)
            ensureSdks(store, defaultDesktopSdk(), root)
            return IdeServices(platform, store, env = env)
        }

        private fun openStore(
            root: Path,
            // The application environment (substrate + container) every opened project shares. A standalone
            // caller (a test / one-off bootstrap with no ProjectManager) gets a fresh transient one.
            env: ApplicationEnvironment = ApplicationEnvironment(),
        ): Pair<PlatformCore, ProjectModelStore> {
            // The per-project platform is a thin CHILD of the app substrate: its extension registry parents the
            // app registry (so app-global host extensions are visible), and its bus + lock ARE the app's. EVERY
            // host + engine contribution now registers ONCE on the app registry (in [registerStaticPlugins]) and
            // resolves the per-project engine through the service scope / [ApplicationEnvironment.activeEngine], so
            // this child registry holds NOTHING project-local — it exists only as the hierarchy shim. (Kept rather
            // than eliminated: its bus/lock are the app's, so its `dispose()` is a no-op over the shared substrate.)
            val platform = PlatformCore(env.platform.extensions, env.platform.messageBus, env.platform.modelLock)
            val store = ProjectModel.open(root, platform, env.codecs, env.container)
            return platform to store
        }

        /**
         * Register the **project-independent** plugin contributions on [platform]'s extension registry:
         * module types, facet codecs, synthetic-class providers, file icons, and the Create-Project
         * templates. These are static (read once into cached registries, never mutated at runtime), so the
         * same set is registered both per-project (via [openStore]) and once at **application** scope (by
         * [ProjectManager], over its own app-level [PlatformCore]) — letting the picker enumerate templates
         * without an open project. Returns the [ModuleTypeRegistry]/[FacetCodecRegistry] the model needs.
         */
        internal fun registerStaticPlugins(
            extensions: ExtensionRegistry,
            env: ApplicationEnvironment,
        ): Pair<ModuleTypeRegistry, FacetCodecRegistry> {
            val moduleTypes = ModuleTypeRegistry(extensions)
            moduleTypes.register(JavaLibModuleType, PluginId("java-support"))
            val codecs = FacetCodecRegistry()
            // android-support: register android-app/-lib module types + the AndroidFacet codec so
            // `module.toml` files of type android-* load with a resolvable type and a decodable facet.
            AndroidSupport.register(moduleTypes, codecs)
            // platform.syntheticClass: the light Android `R` is registered per-project from the IdeServices
            // INSTANCE ([registerInstanceSyntheticClasses]) so it can share that instance's fingerprint-cached
            // resource repository — re-parsing every dependency `res/` per completion/analysis pass OOM'd a
            // tight device. Only the project-independent stand-ins (BuildConfig) register here.
            extensions.register(
                SYNTHETIC_CLASS_EP, AndroidBuildConfigProvider(), PluginId("android-support")
            )
            // ViewBinding: `<namespace>.databinding.<Layout>Binding` per layout, generated from the module's OWN
            // layouts (small, no dependency res), so it needs no shared repo cache and registers statically too.
            extensions.register(
                SYNTHETIC_CLASS_EP, AndroidViewBindingProvider(), PluginId("android-support")
            )
            // Kotlin interop: a module's top-level `fun`/`val` become a `<File>Kt` facade and its classes/
            // objects become types, so Java code (and JDT completion/analysis) resolves them before a build.
            extensions.register(
                SYNTHETIC_CLASS_EP, KotlinSyntheticClassProvider(), PluginId("kotlin-support")
            )
            // platform.fileIcon: the built-in classifier + the Android plugin's res/assets/manifest icons.
            val fileIcons = FileIconRegistry(extensions)
            fileIcons.register(DefaultFileIconProvider, PluginId("platform"))
            AndroidSupport.registerIcons(fileIcons)
            // platform.projectTemplate: the Create-Project gallery — built-in Java + Kotlin templates + Android plugin's.
            val templates = ProjectTemplateRegistry(extensions)
            templates.register(JavaConsoleAppTemplate, PluginId("java-support"))
            templates.register(JavaLibraryTemplate, PluginId("java-support"))
            templates.register(KotlinConsoleAppTemplate, PluginId("kotlin-support"))
            templates.register(KotlinLibraryTemplate, PluginId("kotlin-support"))
            AndroidSupport.registerTemplates(templates)
            // Sample projects (complete, runnable examples) — listed under "Sample projects" in the store.
            templates.register(CalculatorSampleTemplate, PluginId("samples"))
            templates.register(NotesSampleTemplate, PluginId("samples"))
            templates.register(WeatherSampleTemplate, PluginId("samples"))
            // Jetpack Compose sample games (Snake, Tic-Tac-Toe, Memory Match, 2048) — also under "Sample projects".
            AndroidSupport.registerComposeSamples(templates)

            // ---- Stateless, project-independent editor contributions (registered ONCE, app-global) ----
            // These capture no per-project state (their parse hosts are global singletons), so one shared
            // instance per app is correct, and the per-project child registry inherits them via the hierarchy.

            // platform.languageBackend: Java (JDT) first as the default fallback, then XML + Kotlin. The
            // hierarchical registry returns app (parent) contributions before any project-local ones, so this
            // app-global order IS the resolution order (JDT-as-fallback must precede the others).
            val langPlugin = PluginId("language-backends")
            extensions.register(LANGUAGE_BACKEND_EP, JdtLanguageBackend(), langPlugin)
            extensions.register(LANGUAGE_BACKEND_EP, XmlLanguageBackend(), langPlugin)
            extensions.register(LANGUAGE_BACKEND_EP, KotlinLanguageBackend(), langPlugin)

            // platform.completionContributor / platform.postfixTemplate: the cross-cutting completion
            // contributors + the generic postfix-template driver + Kotlin's built-in postfix templates.
            val completionPlugin = PluginId("completion-builtins")
            extensions.register(
                COMPLETION_CONTRIBUTOR_EP,
                CompletionContribution(BufferWordsContributor, order = BufferWordsContributor.ORDER),
                completionPlugin,
            )
            extensions.register(
                COMPLETION_CONTRIBUTOR_EP,
                CompletionContribution(
                    PostfixContributor(extensions),
                    order = PostfixContributor.ORDER,
                ),
                completionPlugin,
            )
            KotlinPostfixTemplates.all().forEach {
                extensions.register(dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP, it, completionPlugin)
            }

            // platform.index: the built-in symbol/member/resource index extensions.
            val indexPlugin = PluginId("indexing")
            listOf(
                JavaClassNamesIndex,
                JavaPackagesIndex,
                JavaPackageTypesIndex,
                JavaClassLocatorIndex, // fqcn -> owning jar, for the index-backed JDT name environment
                JavaSourceSymbolsIndex,
                JavaMembersIndex,
                JavaMembersByOwnerIndex, // cross-language: a Kotlin file enumerating a Java SOURCE class's members
                KotlinTypeShapeIndex, // Kotlin backend: persistent owner-keyed member shapes
                KotlinBuiltinsIndex, // Kotlin backend: intrinsic List/Int/String shapes (.kotlin_builtins)
                KotlinCallableIndex, // Kotlin backend: persistent extensions + top-level callables
                KotlinBuiltinCallableIndex, // Kotlin backend: top-level intrinsics (arrayOf/intArrayOf/… in .kotlin_builtins)
                KotlinSourceCallableIndex, // same key scheme over project .kt (cross-file source extensions)
                KotlinPackageDeclIndex, // per-package top-level classifiers + callables
                JavaSourceDocIndex, // param names + javadoc from attached Java sources
                KotlinSourceDocIndex, // param names + KDoc from attached Kotlin sources
                JavaMainIndex, // runnable entry points in Java source (the Run picker)
                KotlinMainIndex, // runnable entry points in Kotlin source (the Run picker)
                AndroidResourceIndex, // Android resource declarations
                // Direct inheritors + annotated-by (the parity plan's Phase 1.3/1.4), keyed by SHORT name
                // across all three producer sides; consumers merge SubtypeIndex.ALL / AnnotationIndex.ALL.
                BinarySubtypeIndex,
                BinaryAnnotationIndex,
                KotlinSourceSubtypeIndex,
                KotlinSourceAnnotationIndex,
                JavaSourceSubtypeIndex,
                JavaSourceAnnotationIndex,
            ).forEach { extensions.register(INDEX_EP, it, indexPlugin) }

            // platform.blockMapping: the built-in Java block decomposition.
            extensions.register(BLOCK_MAPPING_EP, JavaBlockMapping, PluginId("java-support"))

            // The STATELESS analysis support (analyzers/diagnostic providers/quick-fix providers that capture no
            // per-project state). The XML analysis support is NOT here — it captures a per-project resource host
            // and is registered app-global via the active engine (see [registerActiveEnginePlugins]).
            dev.ide.lang.jdt.analysis.JdtAnalysisSupport.register(extensions)
            dev.ide.lang.kotlin.analysis.KotlinAnalysisSupport.register(extensions)

            // platform.service: this engine family's scoped services. Registered ONCE on the app registry; each
            // factory resolves the per-project engine through the scope ([ENGINE_CONTEXT], published on every
            // engine's own workspace container) rather than closure-capturing it — so one app-global descriptor
            // set serves every opened project. A MODULE/WORKSPACE container resolves the right engine by walking
            // up to where its workspace published ENGINE_CONTEXT.
            registerEngineServices(extensions)

            // The capturing app-level extensions (synthetic-R, command actions, the XML resource host) that have
            // no service scope to resolve through: registered ONCE here, deriving the open engine from [env].
            registerActiveEnginePlugins(extensions, env)

            return moduleTypes to codecs
        }

        /** The `platform.service` descriptors for this engine family's MODULE analyzers + WORKSPACE concern
         *  services. Each factory resolves the per-project engine via [ENGINE_CONTEXT] (the scope walks
         *  MODULE → WORKSPACE to the engine that published it), so a single app-global registration serves every
         *  opened project — no per-project closure capture. */
        private fun registerEngineServices(extensions: ExtensionRegistry) {
            val plugin = PluginId("ide-core-services")
            // MODULE-scoped per-language analyzers: built (and cached/disposed) by each module's container.
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    ANALYZER_JAVA, ServiceScopeLevel.MODULE,
                    { getService(ENGINE_CONTEXT).buildAnalyzer(module(), LanguageId("java")) },
                    plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    ANALYZER_KOTLIN, ServiceScopeLevel.MODULE,
                    { getService(ENGINE_CONTEXT).buildAnalyzer(module(), KotlinLanguageBackend.LANGUAGE_ID) },
                    plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    ANALYZER_XML, ServiceScopeLevel.MODULE,
                    { getService(ENGINE_CONTEXT).buildAnalyzer(module(), XmlLanguageBackend.LANGUAGE_ID) },
                    plugin
                ), plugin
            )
            // WORKSPACE-scoped concern services carved out of the engine. Each pulls its engine's [EngineContext]
            // from the workspace scope it resolves in (the engine published it there in registerScopedServices).
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    SIGNING_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { SigningService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    SEARCH_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { SearchService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    BLOCK_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { BlockService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
            // The action surface is the workspace's ActionManager itself, resolving the toolbar/menu/palette EPs
            // against the resolving engine's own (hierarchical) registry — so it sees app + project contributions.
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    ACTION_MANAGER, ServiceScopeLevel.WORKSPACE,
                    { ActionManager(getService(ENGINE_CONTEXT).platform.extensions) }, plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    DEPENDENCY_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { DependencyService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    MODULE_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { ModuleService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
            extensions.register(
                SERVICE_EP, ServiceDescriptor(
                    BUILD_SERVICE, ServiceScopeLevel.WORKSPACE,
                    { BuildService(getService(ENGINE_CONTEXT)) }, plugin
                ), plugin
            )
        }

        /**
         * Register the app-level extension callbacks that have no service scope to resolve through — they fire
         * outside any project's container (the command palette, the synthetic-class resolver, the XML diagnostic
         * pipeline). Registered ONCE on the app registry; each resolves the open project lazily through
         * [ApplicationEnvironment.activeEngine] at callback time, so the single registration serves every project.
         */
        private fun registerActiveEnginePlugins(extensions: ExtensionRegistry, env: ApplicationEnvironment) {
            // Built-in command-palette actions (Run/Stop build, Re-index): act on the active engine.
            BuiltInActions.register(extensions, env)

            // platform.syntheticClass: the light Android `R`, resolved from the active engine's SHARED
            // fingerprint-cached resource repository (so it reuses the SAME parsed resource set as the
            // preview/value/go-to-def paths). The per-project repository cache is the engine's; this provider
            // is app-global and reaches it through the active engine.
            extensions.register(
                SYNTHETIC_CLASS_EP,
                AndroidRClassProvider { m, _ -> env.activeEngine?.resourceRepo(m) },
                PluginId("android-support"),
            )

            // platform.completionWeigher: the acceptance-frequency stats weigher (IntelliJ's `stats`) —
            // app-global like every weigher, counting through the ACTIVE engine's per-project counters.
            extensions.register(
                dev.ide.lang.completion.COMPLETION_WEIGHER_EP,
                dev.ide.lang.completion.StatsWeigher { item ->
                    env.activeEngine?.completionStats?.countFor(CompletionStats.keyOf(item.label)) ?: 0
                },
                PluginId("completion-builtins"),
            )

            // The XML editor diagnostics: the resource host + Android attribute schema + the app-compat
            // intention, all delegating to the active engine's per-project state.
            dev.ide.lang.xml.lint.XmlAnalysisSupport.register(
                extensions,
                ActiveEngineXmlResourceHost(env),
                AndroidXmlChecker(layout = { env.activeEngine?.sdkLayoutMetadata() ?: AndroidSdkMetadata.bundled() }),
            )
            extensions.register(
                ACTION_PROVIDER_EP,
                AndroidXmlActionProvider { target -> env.activeEngine?.moduleUsesAppCompat(target) ?: false },
                PluginId("android-xml"),
            )
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
            /** On-device live custom-view runtime (from :ide-android) — dex + Bridge classloader for the layout preview. */
            customViewRuntime: CustomViewRuntime? = null,
            /** On-device real-view layout renderer (from :ide-android) — the layoutlib-on-device preview path. */
            realViewRuntime: RealViewRuntime? = null,
            /** On-device Kotlin compiler-plugin loader (from :ide-android): D8-dex + DexClassLoader. */
            kotlinPluginLoader: KotlinPluginLoader? = null,
            /** App-level shared download cache (projects-root parent); null → per-project. */
            sharedCachesRoot: Path? = null,
            /** The host's application environment, so app-scoped substrate + services are shared across projects. */
            env: ApplicationEnvironment = ApplicationEnvironment(),
        ): IdeServices {
            Files.createDirectories(root)
            val (platform, store) = openStore(root, env)
            ensureSdks(store, sdk, sharedCachesRoot ?: root)
            val template = ProjectTemplateRegistry(platform.extensions).byId(TemplateId(templateId))
                ?: error("Unknown project template '$templateId'")
            val templateArgs = TemplateArgs(args)
            template.generate(ScaffoldImpl(store, languageLevel), templateArgs)
            store.save()
            val services = IdeServices(
                platform,
                store,
                androidTools,
                dexRunner,
                apkInstaller,
                customViewRuntime,
                realViewRuntime,
                kotlinPluginLoader,
                sharedCachesRoot,
                env = env,
            )
            // The template's declared Maven dependencies (e.g. Material You's material, or the Compose AAR
            // graph) are DECLARED into `module.toml` now (the durable source of truth for what the project
            // requires) but NOT resolved here — the closure can be large/slow and would block creation.
            // Resolution (→ `libraries.json`) is deferred to the background once the host opens the project
            // ([startPendingDependencyResolution]), so creation returns immediately and the user can work
            // while deps stream in (progress on `depsState`). Decoupling the two means a slow/offline resolve
            // can't drop a declaration: it stays in `module.toml` and the next open retries it.
            val pending = template.dependencies(templateArgs)
            services.dependencies.setPendingDependencies(pending)
            return services
        }

        /** Open the existing workspace at [root] (seeding [sdk] only if it has none). The [ProjectManager.open] core. */
        fun openAt(
            root: Path,
            sdk: SdkData,
            androidTools: AndroidDeviceTools? = null,
            dexRunner: DexRunner? = null,
            apkInstaller: ApkInstaller? = null,
            /** On-device live custom-view runtime (from :ide-android) — dex + Bridge classloader for the layout preview. */
            customViewRuntime: CustomViewRuntime? = null,
            /** On-device real-view layout renderer (from :ide-android) — the layoutlib-on-device preview path. */
            realViewRuntime: RealViewRuntime? = null,
            /** On-device Kotlin compiler-plugin loader (from :ide-android): D8-dex + DexClassLoader. */
            kotlinPluginLoader: KotlinPluginLoader? = null,
            /** App-level shared download cache (projects-root parent); null → per-project. */
            sharedCachesRoot: Path? = null,
            /** The host's application environment, so app-scoped substrate + services are shared across projects. */
            env: ApplicationEnvironment = ApplicationEnvironment(),
            /** Headless build-only engine (the :build daemon): skip the editor cold-start. See [buildOnly]. */
            buildOnly: Boolean = false,
        ): IdeServices {
            val (platform, store) = openStore(root, env)
            ensureSdks(store, sdk, sharedCachesRoot ?: root)
            return IdeServices(
                platform,
                store,
                androidTools,
                dexRunner,
                apkInstaller,
                customViewRuntime,
                realViewRuntime,
                kotlinPluginLoader,
                sharedCachesRoot,
                buildOnly = buildOnly,
                env = env,
            )
        }

        /**
         * Best-effort import of the Gradle project at [root] into the native model, writing a workspace there
         * (so it lists/opens like any project) flagged as **compatibility mode**. Reads the Gradle scripts
         * tolerantly (see [GradleImport]) — the result may have unresolved dependencies and may not build
         * without adjustment. Returns false (writing nothing) if [root] isn't an importable Gradle project.
         */
        fun importGradleProjectAt(root: Path, sdk: SdkData, languageLevel: LanguageLevel): Boolean {
            val spec = GradleImport.parse(root) ?: return false
            val (_, store) = openStore(root)
            ensureSdks(store, sdk, root)
            GradleImport.populate(store, spec, languageLevel)
            store.save()
            GradleImport.markCompatibilityMode(root, spec.report.notes)
            return true
        }
    }
}

/** A surfaced content root with the metadata the tree needs: where it is, its source-set, its roles. */
data class TreeRootInfo(val path: Path, val sourceSetName: String, val roles: Set<ContentRole>)

/**
 * The app-global [dev.ide.lang.xml.lint.XmlResourceHost] for the XML diagnostics pipeline: registered once,
 * it forwards every call to the currently-open engine's per-project host
 * ([IdeServices.xmlResourceHost], resolved through [ApplicationEnvironment.activeEngine]). With no project
 * open it returns the quiet defaults (nothing is view-like, no references, nothing flagged), so the XML
 * pipeline never false-positives before/between projects.
 */
private class ActiveEngineXmlResourceHost(private val env: ApplicationEnvironment) :
    dev.ide.lang.xml.lint.XmlResourceHost {
    private val host get() = env.activeEngine?.xmlResourceHost

    override fun isViewLike(tag: String): Boolean = host?.isViewLike(tag) ?: false
    override fun scanResourceReferences(text: String): List<dev.ide.lang.xml.lint.XmlResourceRef> =
        host?.scanResourceReferences(text) ?: emptyList()
    override fun typeHasAny(file: VirtualFile, rClass: String): Boolean =
        host?.typeHasAny(file, rClass) ?: false
    override fun hasResource(file: VirtualFile, rClass: String, name: String): Boolean =
        host?.hasResource(file, rClass, name) ?: true
    override fun isValueType(rClass: String): Boolean = host?.isValueType(rClass) ?: false
    override fun appendValueResource(file: VirtualFile, rClass: String, name: String, value: String): String =
        host?.appendValueResource(file, rClass, name, value) ?: name
    override fun isFileType(rClass: String): Boolean = host?.isFileType(rClass) ?: false
    override fun createResourceFile(file: VirtualFile, rClass: String, name: String): String? =
        host?.createResourceFile(file, rClass, name)
}

/** Content roles surfaced in the project tree (code + resources + assets); see [IdeServices.treeRoots]. */
private val TREE_ROOT_ROLES = setOf(
    ContentRole.SOURCE,
    ContentRole.GENERATED,
    ContentRole.RESOURCE,
    ContentRole.ANDROID_RES,
    ContentRole.ASSETS,
)

/** The per-file analysis context the engine consumes: live DOM + the module's resolver (Java/Kotlin/XML)
 *  + the index. Backend-neutral — it holds whatever [SourceAnalyzer] the file's language resolved to. */
private class IdeAnalysisTarget(
    override val file: VirtualFile,
    override val parsed: ParsedFile,
    override val documentVersion: Long,
    override val resolver: SourceAnalyzer,
    override val index: IndexService,
    override val module: Module,
) : AnalysisTarget {
    // The engine polls this between analyzers; delegate to the editor-thread cancel token so a completion
    // request can preempt an in-flight analysis pass (the Kotlin path polls its own walk directly).
    override fun checkCanceled() = dev.ide.platform.EngineCancellation.checkCanceled()
}

/** Drives a `suspend` SPI call to completion synchronously (the analyzer never actually suspends). */
private fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

/** Whether the Android platform sources (parameter names + javadoc for `android.*`) are installed/obtainable. */
data class AndroidSourcesInfo(
    val platform: String, val installed: Boolean, val downloadable: Boolean
)

/** The renameable symbol under the caret: its current [oldName] and a human [kind] label (e.g. "method"). */
data class RenameInfo(val oldName: String, val kind: String)

/**
 * Outcome of a project-wide rename: [occurrences] identifiers across [filesChanged] files were rewritten;
 * [newPath] is set when the backing `.java` file was itself renamed (so the editor can reopen it).
 */
data class RenameOutcome(
    val success: Boolean,
    val message: String,
    val occurrences: Int = 0,
    val filesChanged: Int = 0,
    val newPath: String? = null,
)
