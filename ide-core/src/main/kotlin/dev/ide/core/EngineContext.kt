package dev.ide.core

import dev.ide.android.support.tools.KeystoreRegistry
import dev.ide.build.engine.DexRunner
import dev.ide.core.services.DependencyService
import dev.ide.index.IndexService
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import dev.ide.lang.dom.ParsedFile
import dev.ide.model.Module
import dev.ide.model.Project
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Path

/**
 * The shared engine infrastructure a decomposed workspace service depends on, instead of the whole
 * [IdeServices] god class. [IdeServices] exposes itself through this narrow surface (an inner object so it
 * can reach private helpers without widening their visibility) and passes it to each service's factory, so a
 * service sees only what it needs at the type level even though one engine instance backs them all.
 *
 * This is the keystone of the [dev.ide.ui.backend.IdeBackend] decomposition: as later concern services are
 * carved out of [IdeServices], the genuinely-shared state they all need is added here rather than handed the
 * whole engine. Members stay deliberately minimal — only what an extracted service legitimately shares.
 */
internal interface EngineContext {
    /** The workspace model store (atomic mutation + persistence + the scoped service containers). */
    val store: ProjectModelStore

    /** The per-project platform (extension-point registry, message bus, model lock). */
    val platform: PlatformCore

    /** The active workspace's root directory (`store.rootPath`). */
    val workspaceRoot: Path

    /** App-level shared cache root (resolved-deps/index/dex), or null → per-workspace `.platform/caches`. */
    val sharedCachesRoot: Path?

    /** The workspace symbol/member/resource index. Shared infrastructure: completion, analysis, and preview
     *  all read it too, so it stays engine-owned and is reached through here, not owned by the search service. */
    val indexService: IndexService

    /** App-global signing-keystore registry. Shared infrastructure: the Android build's signing resolver
     *  reads it too, so it stays owned by the engine and is reached through here, not by the signing service. */
    val keystoreRegistry: KeystoreRegistry

    /** SDK/JDK download manager. Shared infrastructure: analyzer construction reads its JDK source override. */
    val sdkManager: SdkManagerService

    /** Sibling dependency service (cross-service: enabling a build feature pulls its runtime dependency). */
    val dependencies: DependencyService

    /** APPLICATION-scoped warm K2 compiler (shared across projects); the build drives its incremental wrapper. */
    val kotlinJvmCompiler: KotlinJvmCompiler

    /** The compile bootclasspath (android.jar + desugar stubs on device, empty on desktop). Shared by the
     *  build, the analyzer, and the Kotlin compiler warm-up. */
    val compileBootClasspath: List<Path>

    /** On-device Android tool ports (android.jar + native tools), or null on the desktop / no SDK. */
    val androidTools: AndroidDeviceTools?

    /** On-device dexed-console-app runner (a Java `run` on ART), or null on the desktop. */
    val dexRunner: DexRunner?

    /** On-device APK install+launch port (the Android Run), or null on the desktop. */
    val apkInstaller: ApkInstaller?

    /** [module] plus its transitive in-project module-dependency closure (the modules a build compiles/links). */
    fun moduleBuildClosure(module: Module): List<Module>

    /** The module's source/generated content roots (the dirs a compile/run scans for sources). */
    fun sourceRoots(module: Module): List<Path>

    /** Every module across the workspace's projects. */
    fun modules(): List<Module>

    /** The project that owns [module], or null. */
    fun projectOf(module: Module): Project?

    /** The module's root directory (`<module>/build/classes` → `<module>`), or null on an unexpected layout. */
    fun moduleRoot(module: Module): Path?

    /** Content roots to surface in the project tree (code + resources + assets) for [module]. */
    fun treeRoots(module: Module): List<Path>

    /** The live editor-buffer text for [path] (unsaved edits), or null when the file is not open. */
    fun overlayText(path: Path): String?

    /** Persist every open editor buffer to disk (so a build/run sees the latest, unsaved-included, content). */
    fun flushOpenDocuments()

    /** Provision the bundled kotlin-stdlib as a project dependency for every module with `.kt` sources (a
     *  build calls this before compiling a newly-added Kotlin module). */
    fun ensureKotlinStdlib()

    /** Parse [file]'s buffer [text] into the neutral tolerant DOM via the module's incremental parser, or null
     *  when [file] is outside the project. The shared parse primitive both the editor core and the block
     *  projection ride on. */
    fun parse(file: Path, text: String): ParsedFile?

    /** Construct the [SourceAnalyzer] for [module] in [language]. The app-global module-scoped analyzer service
     *  factory calls this through the scope-resolved engine; the module container caches and disposes it. */
    fun buildAnalyzer(module: Module, language: dev.ide.lang.LanguageId): dev.ide.lang.SourceAnalyzer

    /** The module's merged Android resource repository (fingerprint-cached, shared with the editor's synthetic-R
     *  provider / layout preview / reference resolution), or null when the module has no Android resources. The
     *  app-global synthetic-R provider resolves this through the active engine. */
    fun resourceRepo(module: Module): dev.ide.android.support.resources.ResourceRepository?

    /** Read an app/project-scoped preference (`.platform/settings.properties`), or null if unset. */
    fun projectPref(key: String): String?

    /** Persist a project-scoped preference. */
    fun setProjectPref(key: String, value: String)

    /** The active build-variant name for [module] (persisted choice, else the default variant). */
    fun activeVariant(module: Module): String

    /** All selectable build-variant names for [module] (empty for a non-Android module). */
    fun listVariants(module: Module): List<String>

    /** Select [module]'s active build variant; re-analyzes + re-indexes if it changed. */
    fun setActiveVariant(module: Module, variantName: String)

    /** Drop the cached synthetic classes (Android `R`, ViewBinding, …) so the next analysis re-renders them
     *  (e.g. after a resource/facet/build-feature change). */
    fun invalidateSyntheticClasses()

    /** Rebuild the per-module analyzers (e.g. after a toolchain/source change took effect). */
    fun invalidateAnalyzers()

    /** Re-run the background index build over the current scope. */
    fun resyncIndex()

    /** The workspace change-notification spine. A service that mutated the world PUBLISHES the fact here
     *  (e.g. [WorkspaceEventHub.librariesChanged] after a finished resolution wrote `libraries.json`);
     *  the invalidation chains run as the hub's subscribers. Prefer publishing over calling
     *  [invalidateAnalyzers]/[resyncIndex] directly: the event is what the out-of-process engines see. */
    val events: WorkspaceEventHub
}
