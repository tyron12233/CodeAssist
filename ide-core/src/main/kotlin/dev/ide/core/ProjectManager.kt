package dev.ide.core

import dev.ide.build.engine.DexRunner
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.SdkData
import dev.ide.model.template.TemplateArgs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Paths

/** A project listed in the picker, read cheaply from disk without opening the full engine. */
data class ProjectSummary(val name: String, val rootPath: String, val moduleCount: Int)

/**
 * Owns the on-disk set of projects (one workspace dir per project under [projectsRoot]) and the
 * app-global preferences file (onboarding flag, and similar). Creates/opens projects into a fresh
 * [IdeServices]; the host (desktop or on-device) injects the SDK, language level, and Android tool ports
 * through the [desktop]/[onDevice] factories, so the manager itself is platform-neutral.
 */
class ProjectManager private constructor(
    val projectsRoot: Path,
    private val homeDir: Path,
    private val sdk: () -> SdkData,
    private val languageLevel: LanguageLevel,
    private val androidTools: AndroidDeviceTools?,
    /** Directories whose contents a backup captures — new projects, plus any legacy data on-device. */
    private val backupRoots: List<Path>,
    /** On-device `DexClassLoader` runner (from :ide-android) so a Java `run` works in every opened project. */
    private val dexRunner: DexRunner? = null,
    /** On-device APK installer (from :ide-android) so the android Run works in every opened project. */
    private val apkInstaller: ApkInstaller? = null,
) {
    init {
        Files.createDirectories(projectsRoot)
    }

    private val prefsFile: Path get() = homeDir.resolve("prefs.properties")

    /** Existing projects (subdirs of [projectsRoot] holding a saved model), sorted by name. */
    fun list(): List<ProjectSummary> {
        if (!Files.isDirectory(projectsRoot)) return emptyList()
        val dirs = Files.newDirectoryStream(projectsRoot).use { it.toList() }
        return dirs
            .filter { Files.isDirectory(it) && ModelPersistence.exists(it) }
            .map { dir ->
                val proj = runCatching { ModelPersistence.load(dir) }.getOrNull()?.projects?.firstOrNull()
                ProjectSummary(proj?.name ?: dir.fileName.toString(), dir.toString(), proj?.modules?.size ?: 0)
            }
            .sortedBy { it.name.lowercase() }
    }

    /** True when no project exists yet (first launch / empty state). */
    fun isEmpty(): Boolean = list().isEmpty()

    /** Create a new project from [templateId] with the collected [args]; returns the opened engine. */
    fun create(templateId: String, args: Map<String, String>): IdeServices {
        val name = args[TemplateArgs.NAME]?.takeIf { it.isNotBlank() } ?: "Untitled"
        val dir = uniqueProjectDir(name)
        return IdeServices.createProjectAt(dir, templateId, args, sdk(), languageLevel, androidTools, dexRunner, apkInstaller)
    }

    /** Open the existing project at [rootPath]; returns the opened engine. */
    fun open(rootPath: String): IdeServices = IdeServices.openAt(Paths.get(rootPath), sdk(), androidTools, dexRunner, apkInstaller)

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
        /** Desktop host: an installed Android SDK if present (so `android.*` resolves), else a detected JDK; Java 17. */
        fun desktop(projectsRoot: Path): ProjectManager =
            ProjectManager(
                projectsRoot,
                projectsRoot.parent ?: projectsRoot,
                { IdeServices.defaultDesktopSdk() },
                LanguageLevel.JAVA_17,
                androidTools = null,
                backupRoots = listOf(projectsRoot),
            )

        /**
         * On-device (ART) host: the bundled `android.jar` boot classpath + native tool ports; Java 8.
         * [dataDir] (the app's `filesDir`) is the backup root, so a backup captures both new projects and
         * any project files left by a previous, incompatible app version.
         */
        fun onDevice(
            projectsRoot: Path,
            bootClasspath: List<String>,
            androidToolsDir: Path,
            debugKeystore: Path,
            dataDir: Path,
            /** The host's `DexClassLoader` runner, so a Java console `run` works on ART. */
            dexRunner: DexRunner? = null,
            /** The device's `Build.VERSION.SDK_INT` — min-api the Java dex-run targets. */
            deviceApiLevel: Int = 21,
            /** The host's APK installer, so the android Run (build + install + launch) works on device. */
            apkInstaller: ApkInstaller? = null,
        ): ProjectManager {
            val sdk = SdkData("android", bootClasspath, buildToolsPath = null)
            val tools = AndroidDeviceTools(Paths.get(bootClasspath.first()), androidToolsDir, debugKeystore, deviceApiLevel)
            return ProjectManager(
                projectsRoot,
                projectsRoot.parent ?: projectsRoot,
                { sdk },
                LanguageLevel.JAVA_8,
                tools,
                backupRoots = listOf(dataDir),
                dexRunner = dexRunner,
                apkInstaller = apkInstaller,
            )
        }
    }
}
