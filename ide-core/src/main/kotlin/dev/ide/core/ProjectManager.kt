package dev.ide.core

import dev.ide.build.engine.DexRunner
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.SdkData
import dev.ide.model.template.TemplateArgs
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
) {
    init {
        Files.createDirectories(projectsRoot)
    }

    private val prefsFile: Path get() = homeDir.resolve("prefs.properties")

    /** Directories a backup sweeps: the live projects, plus any legacy data still on disk. */
    private val backupRoots: List<Path> get() = listOf(projectsRoot) + legacyDataDirs

    /** Existing projects (subdirs of [projectsRoot] holding a saved model), sorted by name. */
    fun list(): List<ProjectSummary> {
        if (!Files.isDirectory(projectsRoot)) return emptyList()
        val dirs = Files.newDirectoryStream(projectsRoot).use { it.toList() }
        return dirs
            .filter { Files.isDirectory(it) && ModelPersistence.exists(it) }
            .map { dir ->
                val proj = runCatching { ModelPersistence.load(dir) }.getOrNull()?.projects?.firstOrNull()
                ProjectSummary(
                    proj?.name ?: dir.fileName.toString(),
                    dir.toString(),
                    proj?.modules?.size ?: 0,
                    compatibility = GradleImport.isCompatibilityMode(dir),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** True when no project exists yet (first launch / empty state). */
    fun isEmpty(): Boolean = list().isEmpty()

    /** Create a new project from [templateId] with the collected [args]; returns the opened engine. */
    fun create(templateId: String, args: Map<String, String>): IdeServices {
        val name = args[TemplateArgs.NAME]?.takeIf { it.isNotBlank() } ?: "Untitled"
        val dir = uniqueProjectDir(name)
        return IdeServices.createProjectAt(dir, templateId, args, sdk(), languageLevel, androidTools, dexRunner, apkInstaller, customViewRuntime, sharedCachesRoot = homeDir)
    }

    /** Open the existing project at [rootPath]; returns the opened engine. */
    fun open(rootPath: String): IdeServices =
        IdeServices.openAt(Paths.get(rootPath), sdk(), androidTools, dexRunner, apkInstaller, customViewRuntime, sharedCachesRoot = homeDir)

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
        ): ProjectManager {
            val sdk = SdkData("android", bootClasspath, buildToolsPath = null)
            val tools = AndroidDeviceTools(Paths.get(bootClasspath.first()), androidToolsDir, debugKeystore, deviceApiLevel)
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
            )
        }
    }
}
