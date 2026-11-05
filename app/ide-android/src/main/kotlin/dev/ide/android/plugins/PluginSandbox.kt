package dev.ide.android.plugins

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.FileObserver
import android.os.Process
import dalvik.system.PathClassLoader
import dev.ide.android.SandboxActivity
import dev.ide.core.PluginSandbox
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.platform.log.Log
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import dev.ide.plugin.external.RejectedPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The plugin sandbox: a second copy of the IDE for trying a plugin under development.
 *
 * Not a second install. It is this same app's [SandboxActivity], declared in its own `:sandbox` process, so it
 * runs the same `base.apk` with its own memory, statics and plugin load. What it loads is the plugin build the
 * developer just made, read from a private read-only copy of that APK ([DevApkPluginSource]), never installed
 * and never asked about: it is the developer's own code, and the sandbox exists to run it. Its home is a
 * separate directory ([homeDir]) so its projects, preferences and caches never mix with the real IDE's.
 *
 * The real IDE starts it ([AndroidPluginSandbox.launch], the plugin project's "Run plugin in sandbox") and
 * learns of its crashes through [crashFile], which the sandbox writes as it dies.
 */
object SandboxProcess {
    const val PROCESS_SUFFIX = ":sandbox"

    /** True in the `:sandbox` process. */
    fun isSandbox(context: Context): Boolean = processName(context).endsWith(PROCESS_SUFFIX)

    /** Shared by both processes (app-private internal storage): the plugin build to load and the crash report. */
    fun stateDir(context: Context): File = File(context.applicationContext.filesDir, "plugin-sandbox").apply { mkdirs() }

    /** Names the read-only APK copy the next sandbox start loads. */
    private fun pointer(context: Context) = File(stateDir(context), "current")

    /** The plugin APK this sandbox should load, or null when none was handed over. */
    fun currentApk(context: Context): File? =
        runCatching { pointer(context).readText().trim() }.getOrNull()?.let(::File)?.takeIf { it.isFile }

    /** Written by a sandbox that is dying, read by the real IDE. */
    fun crashFile(context: Context): File = File(stateDir(context), CRASH_FILE)

    /** The sandbox's own app home, beside (not inside) the real IDE's projects. */
    fun homeDir(appHome: File): File = File(appHome, "plugin-sandbox-home")

    internal fun setCurrent(context: Context, apk: File) = pointer(context).writeText(apk.absolutePath)

    internal const val CRASH_FILE = "last-crash.txt"

    /** How a stall report (rather than a crash report) begins, so the host can title it. */
    const val STALL_REPORT_PREFIX = "Stopped the sandbox: "

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(context.packageName)

    /**
     * In the sandbox: a throw that would end the process (one on the main thread) is reported to the real IDE
     * through [crashFile], and then the process ends ITSELF instead of going down the system's crash path.
     *
     * That second half is about Play: Android vitals counts crashes for the whole package, every process of it,
     * and the sandbox is this app. A plugin under development is expected to crash, and those must not land in
     * CodeAssist's crash rate; a process that exits on its own is not a crash report. A throw on any other
     * thread is left to the handler installed earlier, which keeps the sandbox up behind its error dialog.
     */
    fun reportCrashesToHost(context: Context, pluginName: String?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val main = android.os.Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            if (thread !== main) {
                previous?.uncaughtException(thread, t)
                return@setDefaultUncaughtExceptionHandler
            }
            report(
                context,
                buildString {
                    append(pluginName ?: "The plugin").append(" crashed the sandbox on thread '").append(thread.name).append("':\n")
                    append(t.stackTraceToString())
                },
            )
            endQuietly()
        }
    }

    /**
     * In the sandbox: the main thread has been blocked for [stalledMs]. Report where (to the real IDE) and end the
     * process before the system's 5 s input timeout turns it into an ANR, which Play would count against the app
     * just like a crash. Called by the sandbox's [dev.ide.android.MainThreadWatchdog].
     */
    fun stopForStall(context: Context, pluginName: String?, frames: List<StackTraceElement>, stalledMs: Long, blamed: Boolean) {
        report(
            context,
            buildString {
                append(STALL_REPORT_PREFIX)
                append(if (blamed) (pluginName ?: "the plugin") else "something")
                append(" blocked its main thread for ").append(stalledMs / 1000.0).append(" s, and Android would have ")
                append("reported CodeAssist as not responding at 5 s. Move that work off the main thread.\n")
                append("Main thread while it was blocked:\n")
                frames.forEach { append("    at ").append(it).append('\n') }
            },
        )
        endQuietly()
    }

    private fun report(context: Context, text: String) {
        runCatching {
            val tmp = File(stateDir(context), "$CRASH_FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(crashFile(context))
        }
    }

    private fun endQuietly() {
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(0)
    }
}

/**
 * Discovers exactly one plugin: the one packaged in [apk], an APK file that was never installed. Reads its
 * manifest the way the package manager would for an installed plugin app (the marker activity's
 * `dev.ide.codeassist.plugin.manifest` meta-data, resolved against the archive's own resources) and loads its
 * code off that file with the IDE's classloader as parent, like [ApkPluginSource].
 */
class DevApkPluginSource(context: Context, private val apk: File) : PluginSource {
    private val appContext = context.applicationContext
    private val packages: PackageManager get() = appContext.packageManager

    override val id: String get() = SOURCE_ID

    override fun discover(): List<PluginCandidate> = listOfNotNull(read())

    /** The plugin's manifest, or null when [apk] carries none. Public so the sandbox can name it early. */
    fun manifest(): PluginManifest? = (read() as? DiscoveredPlugin)?.manifest

    private fun read(): PluginCandidate? {
        @Suppress("DEPRECATION")
        val info = runCatching {
            packages.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
        }.getOrNull() ?: return reject("not a readable APK")
        val app = info.applicationInfo ?: return reject("the APK has no application")
        // An archive's resources are only reachable once its paths are filled in.
        app.sourceDir = apk.absolutePath
        app.publicSourceDir = apk.absolutePath
        val resourceId = info.activities.orEmpty()
            .firstNotNullOfOrNull { a -> a.metaData?.getInt(ApkPluginSource.META_MANIFEST, 0)?.takeIf { it != 0 } }
            ?: app.metaData?.getInt(ApkPluginSource.META_MANIFEST, 0)?.takeIf { it != 0 }
            ?: return reject("the APK declares no '${ApkPluginSource.META_MANIFEST}' meta-data")
        val manifest = runCatching {
            val text = packages.getResourcesForApplication(app).openRawResource(resourceId)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            PluginManifestToml.parse(text)
        }.getOrElse { return reject(it.message ?: it::class.java.name) }
        return DevPlugin(manifest, PluginOrigin(SOURCE_ID, info.packageName ?: apk.name), apk, javaClass.classLoader)
    }

    private fun reject(reason: String): RejectedPlugin {
        log.warn("cannot load the sandbox plugin from $apk: $reason")
        return RejectedPlugin(PluginOrigin(SOURCE_ID, apk.name), reason, apk.name)
    }

    private class DevPlugin(
        override val manifest: PluginManifest,
        override val origin: PluginOrigin,
        private val apk: File,
        private val parent: ClassLoader?,
    ) : DiscoveredPlugin {
        override fun classLoader(): ClassLoader =
            PathClassLoader(apk.absolutePath, nativeLibraryDir?.toString(), parent)

        /** The APK's `lib/<abi>` for this device, unpacked next to it (an install would have done that). */
        override val nativeLibraryDir: Path? by lazy { unpackNativeLibs(apk) }
    }

    companion object {
        const val SOURCE_ID = "sandbox"
        private val log = Log.logger("PluginSandbox")

        private fun unpackNativeLibs(apk: File): Path? = runCatching {
            val out = File(apk.parentFile, apk.nameWithoutExtension + "-lib")
            ZipFile(apk).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> zip.entries().asSequence().any { it.name.startsWith("lib/$abi/") } }
                    ?: return@runCatching null
                out.mkdirs()
                for (e in zip.entries().asSequence().filter { it.name.startsWith("lib/$abi/") && !it.isDirectory }) {
                    val target = File(out, e.name.substringAfterLast('/'))
                    zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                    target.setExecutable(true, true)
                }
            }
            out.toPath().takeIf { Files.isDirectory(it) }
        }.getOrNull()
    }
}

/**
 * The real IDE's side: hand a plugin build to a fresh sandbox, and relay the sandbox's crashes back.
 */
class AndroidPluginSandbox(
    context: Context,
    /** Called (on a background thread) with the crash report a dying sandbox left. */
    private val onSandboxCrash: (String) -> Unit,
) : PluginSandbox {
    private val appContext = context.applicationContext

    init {
        current = this
    }

    // Held for the IDE's lifetime: a FileObserver that is collected stops observing.
    private val crashWatch = object : FileObserver(SandboxProcess.stateDir(appContext).absolutePath, MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != SandboxProcess.CRASH_FILE) return
            val file = SandboxProcess.crashFile(appContext)
            val text = runCatching { file.readText() }.getOrNull() ?: return
            file.delete()
            runCatching { onSandboxCrash(text) }
        }
    }.also { it.startWatching() }

    override fun launch(apk: Path, log: (String) -> Unit): Boolean {
        val source = apk.toFile()
        if (!source.isFile) {
            log("No APK at $apk: the build did not produce one.")
            return false
        }
        val dir = SandboxProcess.stateDir(appContext)
        // A private copy, read-only: Android refuses to load code from a writable file, and the build output on
        // shared storage is both writable and about to be overwritten by the next build.
        val copy = File(dir, "plugin-${System.currentTimeMillis()}.apk")
        runCatching {
            source.copyTo(copy, overwrite = true)
            copy.setReadOnly()
        }.onFailure {
            log("Could not stage the plugin for the sandbox: ${it.message}")
            return false
        }
        stopRunningSandbox()
        // Older builds and their unpacked libraries: the running sandbox (now stopped) was the last reader.
        dir.listFiles().orEmpty()
            .filter { it != copy && (it.name.startsWith("plugin-")) && !it.name.startsWith(copy.nameWithoutExtension) }
            .forEach { it.deleteRecursively() }
        // ART's compiled code for those builds, which it keeps beside them and never removes by itself.
        File(dir, "oat").deleteRecursively()
        SandboxProcess.crashFile(appContext).delete()
        SandboxProcess.setCurrent(appContext, copy)
        val name = runCatching { DevApkPluginSource(appContext, copy).manifest()?.name }.getOrNull()
        appContext.startActivity(
            Intent(appContext, SandboxActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        log("Started the plugin sandbox with ${name ?: "the plugin"} (${copy.length() / 1024} KB).")
        return true
    }

    companion object {
        /** The UI process's instance, for the build daemon's hand-off (RemoteBuildRunner) to start it. */
        @Volatile
        var current: AndroidPluginSandbox? = null
            private set
    }

    /** Kill the `:sandbox` process if one runs, so the next start is a fresh process with the new build. */
    private fun stopRunningSandbox() {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val sandbox = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
            .firstOrNull { it.processName == appContext.packageName + SandboxProcess.PROCESS_SUFFIX } ?: return
        Process.killProcess(sandbox.pid)
        // Give the system a moment to reap it, so the start below does not land in the dying process.
        repeat(20) {
            val alive = runCatching { am.runningAppProcesses }.getOrNull().orEmpty().any { it.pid == sandbox.pid }
            if (!alive) return
            Thread.sleep(50)
        }
    }
}
