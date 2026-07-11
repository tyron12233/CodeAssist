package dev.ide.core

import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.resources.LauncherIcon
import dev.ide.android.support.tools.KeystoreRegistry
import dev.ide.build.engine.DexRunner
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.model.PlatformKind
import dev.ide.model.impl.SdkData
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.platform.ServiceContainer
import dev.ide.platform.ServiceKey
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.PlatformCore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Paths


/** A project listed in the picker, read cheaply from disk without opening the full engine. */
data class ProjectSummary(
    val name: String,
    val rootPath: String,
    val moduleCount: Int,
    /** True when this project was imported from a Gradle project and runs in compatibility mode. */
    val compatibility: Boolean = false,
    /** True when the project has an Android module (the picker then tries to show its launcher icon). */
    val isAndroid: Boolean = false,
    /** Epoch-ms of the last time the project was opened by the user (0 = never recorded). Drives the
     *  picker's most-recent-first ordering. */
    val lastOpened: Long = 0L,
)

/**
 * Owns the on-disk set of projects (one workspace dir per project under [projectsRoot]) and the
 * app-global preferences file (onboarding flag, and similar). Creates/opens projects into a fresh
 * [IdeServices]; the host (desktop or on-device) injects the SDK, language level, and Android tool ports
 * through the [desktop]/[onDevice] factories, so the manager itself is platform-neutral.
 */
class ProjectManager private constructor(
    val projectsRoot: Path,
    private val homeDir: Path,
    /** The directory a file manager should browse — the app's storage root, which may sit above
     *  [projectsRoot] and hold sibling data (e.g. projects from a previous app version). */
    val storageRoot: Path,
    private val sdk: () -> SdkData,
    private val languageLevel: LanguageLevel,
    private val androidTools: AndroidDeviceTools?,
    /** Legacy on-device data homes (e.g. a previous internal-storage root) that a backup also sweeps up and
     *  that [importLegacyProjects] recovers projects from. Empty on desktop. */
    private val legacyDataDirs: List<Path>,
    /** On-device `DexClassLoader` runner (from :ide-android) so a Java `run` works in every opened project. */
    private val dexRunner: DexRunner? = null,
    /** On-device APK installer (from :ide-android) so the android Run works in every opened project. */
    private val apkInstaller: ApkInstaller? = null,
    /** On-device live custom-view runtime (from :ide-android) so the layout preview renders live custom
     *  views in every opened project, not just the first-run demo. */
    private val customViewRuntime: dev.ide.preview.impl.CustomViewRuntime? = null,
    /** On-device real-view layout renderer (from :ide-android): the layoutlib-on-device preview path. */
    private val realViewRuntime: dev.ide.preview.impl.RealViewRuntime? = null,
    /** On-device Kotlin compiler-plugin loader (from :ide-android): D8-dex + DexClassLoader, so runtime
     *  (non-bundled) Kotlin compiler plugins can be applied on ART. Null on desktop (URLClassLoader default). */
    private val kotlinPluginLoader: dev.ide.lang.kotlin.compile.KotlinPluginLoader? = null,
) {
    init {
        Files.createDirectories(projectsRoot)
    }

    /**
     * The application environment — created once, shared by every opened project: the app-global extension
     * registry + message bus + model lock, the process-global application service container (parent of every
     * project's workspace container), and the host plugin registrations. All application *bootstrap* lives in
     * [ApplicationEnvironment], so this manager is purely about *managing* projects. Disposed by [dispose].
     */
    val env: ApplicationEnvironment = ApplicationEnvironment()

    /** The process-global application service container (see [env]); parents every project container. */
    val applicationContainer: ServiceContainer get() = env.container

    /**
     * The Create-Project gallery templates, enumerable without an open project (the picker shows them
     * before any engine exists). Resolved from the APPLICATION-scoped [PROJECT_TEMPLATES] service.
     */
    fun projectTemplates(): List<ProjectTemplate> = applicationContainer.getService(PROJECT_TEMPLATES).all()

    /**
     * The shared SDK / toolchain download manager — APPLICATION-scoped, so one download queue + resumable
     * cache serves every project AND the project picker's Settings & Tools hub (reachable with no project
     * open). Self-registers on the application container; an opened engine resolves the very same instance,
     * so a download started from the picker is still visible after a project opens. Its on-disk artifacts
     * live under [homeDir] (the same shared dir an engine uses), so the registrant's exact path doesn't matter.
     */
    fun sdkManager(): SdkManagerService {
        applicationContainer.registerServiceIfAbsent(APP_SDK_MANAGER) {
            SdkManagerService(homeDir, sharedRoot = homeDir)
        }
        return applicationContainer.getService(APP_SDK_MANAGER)
    }

    /**
     * The shared signing-keystore registry — APPLICATION-scoped (keystores + their secrets live under
     * [homeDir], shared across projects and kept OUT of any project). Reachable from the picker's hub with
     * no project open. The path matches an engine's (`<homeDir>/keystores`), so both resolve one registry.
     */
    fun keystoreRegistry(): KeystoreRegistry {
        applicationContainer.registerServiceIfAbsent(APP_KEYSTORE_REGISTRY) {
            KeystoreRegistry(homeDir.resolve("keystores"))
        }
        return applicationContainer.getService(APP_KEYSTORE_REGISTRY)
    }

    private val prefsFile: Path get() = homeDir.resolve("prefs.properties")

    /** Directories a backup sweeps: the live projects, plus any legacy data still on disk. */
    private val backupRoots: List<Path> get() = listOf(projectsRoot) + legacyDataDirs

    /**
     * Existing projects (subdirs of [projectsRoot] holding a saved model), most-recently-opened first
     * (projects never opened since this was recorded fall to the bottom, ordered by name). The last-opened
     * timestamps live in the shared prefs file, stamped by [recordOpened] on open/create.
     */
    fun list(): List<ProjectSummary> {
        if (!Files.isDirectory(projectsRoot)) return emptyList()
        val dirs = Files.newDirectoryStream(projectsRoot).use { it.toList() }
        val prefs = loadPrefs()
        return dirs
            .filter { Files.isDirectory(it) && ModelPersistence.exists(it) }
            .map { dir ->
                val proj = runCatching { ModelPersistence.load(dir) }.getOrNull()?.projects?.firstOrNull()
                ProjectSummary(
                    proj?.name ?: dir.fileName.toString(),
                    dir.toString(),
                    proj?.modules?.size ?: 0,
                    compatibility = GradleImport.isCompatibilityMode(dir),
                    isAndroid = proj?.modules?.any { m -> m.facets.any { it.tomlTable == AndroidFacetCodec.tomlTable } } ?: false,
                    lastOpened = prefs.getProperty(openedKey(dir))?.toLongOrNull() ?: 0L,
                )
            }
            .sortedWith(compareByDescending<ProjectSummary> { it.lastOpened }.thenBy { it.name.lowercase() })
    }

    /** Prefs key holding a project's last-opened timestamp (keyed by its unique workspace directory name). */
    private fun openedKey(dir: Path): String = "project.opened.${dir.fileName}"

    /**
     * Stamp [dir] as opened *now*, so the picker floats it to the top. Guarded to a direct child of
     * [projectsRoot] (scratch/build-daemon dirs live elsewhere and never affect the picker order), and
     * best-effort — a failed prefs write must never block opening a project.
     */
    private fun recordOpened(dir: Path) {
        val normalized = dir.toAbsolutePath().normalize()
        if (normalized.parent != projectsRoot.toAbsolutePath().normalize()) return
        runCatching { setPreference(openedKey(normalized), System.currentTimeMillis().toString()) }
    }

    /** True when no project exists yet (first launch / empty state). */
    fun isEmpty(): Boolean = list().isEmpty()

    /** Create a new project from [templateId] with the collected [args]; returns the opened engine. */
    fun create(templateId: String, args: Map<String, String>): IdeServices {
        val name = args[TemplateArgs.NAME]?.takeIf { it.isNotBlank() } ?: "Untitled"
        val dir = uniqueProjectDir(name)
        return IdeServices.createProjectAt(dir, templateId, args, sdk(), languageLevel, androidTools, dexRunner, apkInstaller, customViewRuntime, realViewRuntime = realViewRuntime, kotlinPluginLoader = kotlinPluginLoader, sharedCachesRoot = homeDir, env = env)
            .also { recordOpened(dir) }
    }

    /** Open the existing project at [rootPath]; returns the opened engine. [buildOnly] opens a headless
     *  build engine (the `:build` daemon) that skips the editor cold-start — see [IdeServices]. */
    fun open(rootPath: String, buildOnly: Boolean = false): IdeServices =
        IdeServices.openAt(Paths.get(rootPath), sdk(), androidTools, dexRunner, apkInstaller, customViewRuntime, realViewRuntime = realViewRuntime, kotlinPluginLoader = kotlinPluginLoader, sharedCachesRoot = homeDir, env = env, buildOnly = buildOnly)
            // A build-only daemon open isn't a user "access" — don't let a background build reorder the picker.
            .also { if (!buildOnly) recordOpened(Paths.get(rootPath)) }

    // --- learning scratch projects (hidden; used to compile + run Learn exercises) ---

    /** Cached hidden scratch engines, keyed by their console template id (`java-console` / `kotlin-console`). */
    private val scratchEngines = HashMap<String, IdeServices>()

    /**
     * A hidden, cached single-module project used to compile + run interactive learning exercises. It lives
     * under `<home>/.scratch/<templateId>` — OUTSIDE [projectsRoot], so it never appears in [list] / the
     * picker. Created once from the bundled console template ([templateId] = `"java-console"` |
     * `"kotlin-console"`), then reused across the session and across launches. The Learn checker overwrites
     * its `Main` source per exercise and runs it through [IdeServices.runAndCapture]; the lesson editor also
     * completes against it, so it opens as a FULL engine (not build-only) to have the editor/language stack.
     */
    fun scratch(key: String, templateId: String = key, args: Map<String, String> = emptyMap()): IdeServices {
        scratchEngines[key]?.let { return it }
        val dir = homeDir.resolve(".scratch").resolve(key)
        val services =
            if (ModelPersistence.exists(dir)) open(dir.toString())
            else IdeServices.createProjectAt(
                dir, templateId, mapOf(TemplateArgs.NAME to key) + args, sdk(), languageLevel, androidTools,
                dexRunner, apkInstaller, customViewRuntime, realViewRuntime = realViewRuntime,
                kotlinPluginLoader = kotlinPluginLoader, sharedCachesRoot = homeDir, env = env,
            )
        scratchEngines[key] = services
        return services
    }

    /**
     * Import the Gradle project at [sourceDir] into a new workspace under [projectsRoot] (copying its
     * sources, minus build outputs), build a best-effort compatibility-mode model from its scripts, and open
     * it. Returns null when [sourceDir] isn't an importable Gradle project or the import fails.
     */
    fun importGradleProject(sourceDir: Path): IdeServices? {
        if (!GradleImport.isGradleProject(sourceDir)) return null
        val dest = uniqueProjectDir(sourceDir.fileName?.toString() ?: "gradle-project")
        val ok = runCatching {
            copyTree(sourceDir, dest)
            IdeServices.importGradleProjectAt(dest, sdk(), languageLevel)
        }.getOrDefault(false)
        if (!ok) { runCatching { deleteTree(dest) }; return null }
        return open(dest.toString())
    }

    /**
     * Permanently delete the project rooted at [rootPath] from disk. Guarded to a direct child of
     * [projectsRoot] so a stray path can never wipe an unrelated directory; a missing project is a no-op.
     */
    fun delete(rootPath: String) {
        val dir = Paths.get(rootPath).toAbsolutePath().normalize()
        val root = projectsRoot.toAbsolutePath().normalize()
        require(dir.parent == root && dir != root) { "Refusing to delete a path outside the projects directory: $dir" }
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // --- preferences (onboarding flag, last project, …) ---

    fun preference(key: String): String? = loadPrefs().getProperty(key)

    fun setPreference(key: String, value: String) {
        val props = loadPrefs().apply { setProperty(key, value) }
        Files.createDirectories(prefsFile.parent)
        Files.newOutputStream(prefsFile).use { props.store(it, "CodeAssist preferences") }
    }

    private fun loadPrefs(): Properties = Properties().apply {
        if (Files.exists(prefsFile)) Files.newInputStream(prefsFile).use { load(it) }
    }

    // --- backup ---

    /**
     * Zip every [backupRoots] tree into a single `.zip` under `<home>/exports`, skipping build outputs,
     * caches, the bundled SDK/keystore, and prior exports. On-device this captures the project
     * sources (including any from a previous, incompatible app version still on disk). Returns the
     * created zip.
     */
    fun exportBackup(): Path {
        val exportsDir = homeDir.resolve("exports").also { Files.createDirectories(it) }
        val dest = exportsDir.resolve("codeassist-backup-${System.currentTimeMillis()}.zip")
        ZipOutputStream(Files.newOutputStream(dest)).use { zip ->
            for (root in backupRoots) {
                if (!Files.exists(root)) continue
                val prefix = root.fileName?.toString() ?: "backup"
                Files.walk(root).use { stream ->
                    stream.filter { Files.isRegularFile(it) && !isExcluded(root, it) }.forEach { file ->
                        val rel = root.relativize(file).toString().replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry("$prefix/$rel"))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
        return dest
    }

    /** Bulky/derived/bundled files that don't belong in a project backup. */
    private fun isExcluded(root: Path, file: Path): Boolean {
        val name = file.fileName.toString()
        if (name == "android.jar" || name == "debug.keystore") return true
        val rel = root.relativize(file).toString().replace(File.separatorChar, '/')
        if (rel.contains(".platform/caches/")) return true
        return rel.split('/').any { it == "build" || it == "exports" || it == ".gradle" }
    }

    // --- shareable project packages (.caproj) ---

    /**
     * Export the project at [rootPath] to a `.caproj` under `<home>/exports` (a fresh, non-clobbering file
     * name derived from the project name) and return it. The package carries the project's source-of-truth
     * plus a [CaprojManifest] and, when [ProjectPackaging.ExportOptions.bundleDependencies] is set, the
     * resolved dependency cache so the recipient can build offline. See [ProjectPackaging].
     */
    internal fun exportProject(rootPath: String, options: ProjectPackaging.ExportOptions): Path {
        val projectDir = Paths.get(rootPath)
        val meta = exportMeta(projectDir)
        val exportsDir = homeDir.resolve("exports").also { Files.createDirectories(it) }
        val base = slug(meta.name).ifEmpty { "project" }
        var out = exportsDir.resolve("$base.${CaprojFormat.EXTENSION}")
        var n = 2
        while (Files.exists(out)) { out = exportsDir.resolve("$base-$n.${CaprojFormat.EXTENSION}"); n++ }
        return ProjectPackaging.export(projectDir, out, options, exportIcon(projectDir), meta)
    }

    /** Read a `.caproj`'s manifest, file peek, and icon for the import preview, without extracting it. */
    internal fun readPackagePreview(archivePath: String): ProjectPackaging.Preview? =
        ProjectPackaging.readPreview(Paths.get(archivePath))

    /**
     * Import the `.caproj` at [archivePath] into a new workspace under [projectsRoot] and open it. Returns
     * null when the archive isn't a valid package, its format is newer than this build understands, or the
     * extracted tree isn't a loadable workspace (the half-written directory is cleaned up).
     */
    fun importProject(archivePath: String): IdeServices? {
        val archive = Paths.get(archivePath)
        val preview = ProjectPackaging.readPreview(archive) ?: return null
        if (preview.manifest.format > CaprojFormat.FORMAT_VERSION) return null
        val dest = uniqueProjectDir(preview.manifest.name)
        val ok = runCatching { ProjectPackaging.unpack(archive, dest) }.isSuccess && ModelPersistence.exists(dest)
        if (!ok) { runCatching { deleteTree(dest) }; return null }
        return open(dest.toString())
    }

    /** Project-derived package metadata: name, module list, and Android app namespace read cheaply from the model. */
    private fun exportMeta(projectDir: Path): ProjectPackaging.ExportMeta {
        val project = runCatching { ModelPersistence.load(projectDir) }.getOrNull()?.projects?.firstOrNull()
        val androidFacets = project?.modules?.mapNotNull { m ->
            m.facets.firstOrNull { it.tomlTable == AndroidFacetCodec.tomlTable }?.let { AndroidFacetCodec.decode(it.values) }
        } ?: emptyList()
        val appFacet = androidFacets.firstOrNull { it.isApplication } ?: androidFacets.firstOrNull()
        return ProjectPackaging.ExportMeta(
            name = project?.name ?: projectDir.fileName?.toString() ?: "project",
            isAndroid = androidFacets.isNotEmpty(),
            packageName = appFacet?.namespace,
            modules = project?.modules?.map { it.name } ?: emptyList(),
            createdBy = CaprojFormat.APP_NAME,
            exportedAt = System.currentTimeMillis(),
        )
    }

    /** The Android launcher icon's raster bytes for the package preview, or null (non-raster icons fall back
     *  to the initial-letter tile in the importer, matching the picker). */
    private fun exportIcon(projectDir: Path): ByteArray? {
        val icon = runCatching { ProjectIconLocator.locate(projectDir) }.getOrNull()
        return (icon as? LauncherIcon.Raster)?.let { runCatching { Files.readAllBytes(it.path) }.getOrNull() }
    }

    // --- legacy project recovery ---

    /**
     * One-time recovery for users upgrading from a build that kept projects in internal app storage (before
     * the move to external app storage): copy every loadable project workspace found under [legacyDataDirs]
     * into [projectsRoot] so it reappears in the picker. Non-destructive — the originals stay in place (and
     * remain part of a backup) — and guarded by a preference so it runs at most once. Returns the number of
     * projects recovered. Only finds workspaces in the current on-disk model format; data from an
     * incompatible older app is left for [exportBackup] / the file manager.
     */
    fun importLegacyProjects(): Int {
        if (preference(LEGACY_IMPORTED_PREF) == "true") return 0
        var imported = 0
        for (legacy in legacyDataDirs) {
            // Current-format workspaces (e.g. this app's earlier internal-storage projects): copy verbatim.
            for (src in legacyProjectDirs(legacy) { ModelPersistence.exists(it) }) {
                val dest = uniqueProjectDir(src.fileName.toString())
                if (runCatching { copyTree(src, dest) }.isSuccess) imported++
                else runCatching { deleteTree(dest) } // drop a half-copied directory
            }
            // Legacy Gradle projects (e.g. v0.2.9): copy sources, then build a compatibility-mode model.
            for (src in legacyProjectDirs(legacy) { GradleImport.isGradleProject(it) }) {
                val dest = uniqueProjectDir(src.fileName.toString())
                val ok = runCatching {
                    copyTree(src, dest)
                    IdeServices.importGradleProjectAt(dest, sdk(), languageLevel)
                }.getOrDefault(false)
                if (ok) imported++ else runCatching { deleteTree(dest) }
            }
        }
        setPreference(LEGACY_IMPORTED_PREF, "true")
        return imported
    }

    /** Direct children of a legacy home (and of its `projects/` subdir) matching [accept]. */
    private fun legacyProjectDirs(legacy: Path, accept: (Path) -> Boolean): List<Path> {
        if (!Files.isDirectory(legacy)) return emptyList()
        val bases = listOf(legacy, legacy.resolve("projects")).filter { Files.isDirectory(it) }
        val found = LinkedHashSet<Path>()
        for (base in bases) {
            runCatching {
                Files.newDirectoryStream(base).use { stream ->
                    for (dir in stream) if (Files.isDirectory(dir) && accept(dir)) found.add(dir)
                }
            }
        }
        return found.toList()
    }

    /** Recursive copy, skipping bulky/derived trees (build outputs, caches) the way [exportBackup] does. */
    private fun copyTree(src: Path, dest: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path).toString().replace(File.separatorChar, '/')
                if (rel.contains(".platform/caches/") ||
                    rel.split('/').any { it == "build" || it == ".gradle" }
                ) return@forEach
                val target = dest.resolve(src.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** Dispose application-scoped services + the app extension registry. Call on app exit, after the open
     *  project is closed. */
    fun dispose() {
        scratchEngines.values.forEach { runCatching { it.close() } }
        scratchEngines.clear()
        runCatching { env.close() }
    }

    private fun uniqueProjectDir(name: String): Path {
        val base = slug(name).ifEmpty { "project" }
        var candidate = projectsRoot.resolve(base)
        var n = 2
        while (Files.exists(candidate)) {
            candidate = projectsRoot.resolve("$base-$n"); n++
        }
        return candidate
    }

    private fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    companion object {
        private const val LEGACY_IMPORTED_PREF = "legacy.projects.imported"

        /** Desktop host: an installed Android SDK if present (so `android.*` resolves), else a detected JDK; Java 17. */
        fun desktop(projectsRoot: Path, legacyDataDirs: List<Path> = emptyList()): ProjectManager =
            ProjectManager(
                projectsRoot,
                projectsRoot.parent ?: projectsRoot,
                storageRoot = projectsRoot.parent ?: projectsRoot,
                { IdeServices.defaultDesktopSdk() },
                LanguageLevel.JAVA_17,
                androidTools = null,
                legacyDataDirs = legacyDataDirs,
            )

        /**
         * On-device (ART) host: the bundled `android.jar` boot classpath + native tool ports; Java 8.
         * A backup captures the live projects under [projectsRoot] plus every [legacyDataDirs] tree, and
         * [importLegacyProjects] copies any loadable projects out of those trees into [projectsRoot], so
         * project files left by a previous app version (e.g. in internal storage before the move to external
         * app storage) reappear in the picker and stay recoverable.
         */
        fun onDevice(
            projectsRoot: Path,
            bootClasspath: List<String>,
            androidToolsDir: Path,
            debugKeystore: Path,
            /** The app's external storage root (`getExternalFilesDir(null)`), browsed by a file manager;
             *  sits above [projectsRoot] and holds sibling data like a previous version's projects. */
            storageRoot: Path,
            /** Extra directories a backup should also sweep up — a legacy internal-storage home and the
             *  previous app version's projects directory. */
            legacyDataDirs: List<Path> = emptyList(),
            /** The host's `DexClassLoader` runner, so a Java console `run` works on ART. */
            dexRunner: DexRunner? = null,
            /** The device's `Build.VERSION.SDK_INT` — min-api the Java dex-run targets. */
            deviceApiLevel: Int = 21,
            /** The host's APK installer, so the android Run (build + install + launch) works on device. */
            apkInstaller: ApkInstaller? = null,
            /** The host's live custom-view runtime, so the layout preview renders custom views in every project. */
            customViewRuntime: dev.ide.preview.impl.CustomViewRuntime? = null,
            /** The host's real-view layout renderer (layoutlib-on-device), so the preview can render real views. */
            realViewRuntime: dev.ide.preview.impl.RealViewRuntime? = null,
            /** The host's ART Kotlin compiler-plugin loader (D8-dex + DexClassLoader), so runtime Kotlin
             *  compiler plugins can be applied on device. */
            kotlinPluginLoader: dev.ide.lang.kotlin.compile.KotlinPluginLoader? = null,
            /** The host's forked-VM R8 shrinker (`dalvikvm64 -Xmx…`), so the release/minify R8 pass gets a heap
             *  above the app cap. Null → in-process R8 (the shrinker also self-falls-back if forking fails). */
            r8Shrinker: dev.ide.android.support.tools.Shrinker? = null,
            /** The host's forked-VM D8 dexer for the dex merge step (debug-path memory peak). Null → in-process. */
            r8MergeDexer: dev.ide.android.support.tools.Dexer? = null,
            /** Max class-dex per merge batch on a large app (the "Dex merge batch size" setting); read per build. */
            mergeChunkProvider: () -> Int = { dev.ide.core.settings.BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT },
        ): ProjectManager {


            val sdk = SdkData("android", bootClasspath, buildToolsPath = null, kind = PlatformKind.ANDROID)
            // android.jar is the first boot entry; later entries (the desugar stubs) join the compile platform.
            val tools = AndroidDeviceTools(Paths.get(bootClasspath.first()), androidToolsDir, debugKeystore, deviceApiLevel,
                desugarStubs = bootClasspath.drop(1).map { Paths.get(it) }, r8Shrinker = r8Shrinker, r8MergeDexer = r8MergeDexer,
                mergeChunkProvider = mergeChunkProvider)
            return ProjectManager(
                projectsRoot,
                projectsRoot.parent ?: projectsRoot,
                storageRoot,
                { sdk },
                LanguageLevel.JAVA_8,
                tools,
                legacyDataDirs = legacyDataDirs,
                dexRunner = dexRunner,
                apkInstaller = apkInstaller,
                customViewRuntime = customViewRuntime,
                realViewRuntime = realViewRuntime,
                kotlinPluginLoader = kotlinPluginLoader,
            )
        }
    }
}
