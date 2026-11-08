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
import dev.ide.android.support.BuiltApks
import dev.ide.core.PluginSandbox
import dev.ide.core.plugins.PluginExecutables
import dev.ide.core.plugins.SandboxLogLine
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

    /** The sandbox plugin's log records, one per line, appended by the sandbox and read by the real IDE. */
    internal const val LOG_FILE = "plugin-log.txt"

    internal fun logFile(context: Context): File = File(stateDir(context), LOG_FILE)

    /** Past this the sandbox stops relaying (a plugin logging in a loop must not fill the device). */
    private const val LOG_FILE_CAP = 4L * 1024 * 1024

    /**
     * In the sandbox: append every record the plugin under test logs ([pluginId] is its records' source) to
     * [logFile], where the real IDE picks it up ([AndroidPluginSandbox]) and shows it in its own Logs viewer.
     * Written on a thread of its own, so a plugin logging on the main thread does no disk work there.
     */
    fun relayPluginLogs(context: Context, pluginId: String) {
        val file = logFile(context)
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sandbox-log-relay").apply { isDaemon = true }
        }
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        dev.ide.platform.log.Log.addSink { record ->
            if (record.source != pluginId || stopped.get()) return@addSink
            val line = SandboxLogLine.encode(record)
            writer.execute {
                if (stopped.get()) return@execute
                runCatching {
                    if (file.length() > LOG_FILE_CAP) {
                        stopped.set(true)
                        file.appendText(SandboxLogLine.encodeNote(pluginId, "The sandbox stopped relaying this plugin's log at 4 MB."))
                    } else {
                        file.appendText(line)
                    }
                }
            }
        }
    }

    /** Written by the real IDE: the path of a new build for the running sandbox to swap in ([listenForReloads]). */
    internal const val RELOAD_REQUEST = "reload-request"

    /** Written by the sandbox in answer to [RELOAD_REQUEST]: [ReloadAnswer] as text. */
    internal const val RELOAD_RESULT = "reload-result"

    internal fun reloadResultFile(context: Context): File = File(stateDir(context), RELOAD_RESULT)

    /** What the sandbox answers a reload request with: whether the new build is in, and anything to say. A
     *  failed reload is followed by a fresh start, which loads the build the usual way (and shows on the
     *  plugin's row there why it did not load, if it still does not). */
    data class ReloadAnswer(val ok: Boolean, val message: String) {
        internal fun encode(): String = (if (ok) "ok\n" else "fail\n") + message.replace('\n', ' ')

        internal companion object {
            fun decode(text: String): ReloadAnswer? {
                val parts = text.split('\n', limit = 2)
                if (parts.size < 2) return null
                return ReloadAnswer(parts[0] == "ok", parts[1])
            }
        }
    }

    // Held for the sandbox's lifetime: a collected FileObserver stops observing.
    @Volatile
    private var reloadWatch: FileObserver? = null

    /**
     * In the sandbox: swap in the plugin build the real IDE names in [RELOAD_REQUEST], through [reload], and
     * answer in [RELOAD_RESULT]. One at a time, on a thread of its own ([reload] hops to the main thread for
     * the UI half itself).
     */
    fun listenForReloads(context: Context, reload: (File) -> ReloadAnswer) {
        val dir = stateDir(context)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sandbox-reload").apply { isDaemon = true }
        }
        reloadWatch = object : FileObserver(dir.absolutePath, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != RELOAD_REQUEST) return
                worker.execute {
                    val request = File(dir, RELOAD_REQUEST)
                    val apk = runCatching { request.readText().trim() }.getOrNull()?.let(::File) ?: return@execute
                    request.delete()
                    val answer = runCatching { reload(apk) }
                        .getOrElse { ReloadAnswer(false, it.message ?: it.toString()) }
                    runCatching {
                        val tmp = File(dir, "$RELOAD_RESULT.tmp")
                        tmp.writeText(answer.encode())
                        tmp.renameTo(reloadResultFile(context))
                    }
                }
            }
        }.also { it.startWatching() }
    }

    /**
     * In the sandbox: swap the loaded plugin for the build in [apk], live. The UI half comes out first (on the
     * main thread, so nothing of the old build draws while its engine half is torn down), then the engine half
     * is replaced ([ApplicationEnvironment.reloadInstalledPlugin]), then the new UI half goes in.
     */
    fun reloadPlugin(context: Context, env: dev.ide.core.ApplicationEnvironment, apk: File): ReloadAnswer {
        val discovered = when (val c = DevApkPluginSource(context, apk).discover().firstOrNull()) {
            is DiscoveredPlugin -> c
            is RejectedPlugin -> return ReloadAnswer(false, c.reason)
            else -> return ReloadAnswer(false, "the build has no plugin in it")
        }
        val id = discovered.manifest.id
        val oldUi = env.enabledUiPlugins.firstOrNull { it.id == id }
        onMainThread { dev.ide.ui.ext.UiPluginHost.replace(id, null) }
        return when (val r = env.reloadInstalledPlugin(discovered)) {
            is dev.ide.core.ApplicationEnvironment.PluginReload.Reloaded -> {
                val contributed = onMainThread { dev.ide.ui.ext.UiPluginHost.replace(id, r.ui) } ?: false
                if (contributed) {
                    ReloadAnswer(true, "")
                } else {
                    val why = dev.ide.ui.ext.UiPluginHost.failures[id]
                    ReloadAnswer(false, "its UI facet failed: ${why?.let { PluginLoadFailureText.of(it) } ?: "did not answer"}")
                }
            }
            is dev.ide.core.ApplicationEnvironment.PluginReload.Failed -> {
                // Still the old build, untouched: put its UI back for as long as it keeps running.
                if (!r.unloaded) onMainThread { dev.ide.ui.ext.UiPluginHost.replace(id, oldUi) }
                ReloadAnswer(false, r.reason)
            }
        }
    }

    /** Run [body] on the main thread and wait for it (up to a few seconds); null when it did not run in time. */
    private fun <T> onMainThread(body: () -> T): T? {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) return body()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        android.os.Handler(main).post {
            try {
                result = body()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_THREAD_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) return null
        failure?.let { throw it }
        return result
    }

    private const val MAIN_THREAD_WAIT_S = 5L

    /** The plugin's last few log records, for a crash report: what it was doing just before. */
    private fun recentPluginLog(pluginId: String?): String {
        if (pluginId == null) return ""
        val records = dev.ide.platform.log.Log.recent().filter { it.source == pluginId }.takeLast(15)
        if (records.isEmpty()) return ""
        return buildString {
            append("\nThe plugin's last log lines:\n")
            records.forEach { append("    ").append(it.level.name).append(' ').append(it.tag).append(": ").append(it.message.replace("\n", "\n        ")).append('\n') }
        }
    }

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
    fun reportCrashesToHost(context: Context, pluginName: String?, pluginId: String? = null) {
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
                    append(recentPluginLog(pluginId))
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
    fun stopForStall(
        context: Context,
        pluginName: String?,
        frames: List<StackTraceElement>,
        stalledMs: Long,
        blamed: Boolean,
        pluginId: String? = null,
    ) {
        report(
            context,
            buildString {
                append(STALL_REPORT_PREFIX)
                append(if (blamed) (pluginName ?: "the plugin") else "something")
                append(" blocked its main thread for ").append(stalledMs / 1000.0).append(" s, and Android would have ")
                append("reported CodeAssist as not responding at 5 s. Move that work off the main thread.\n")
                append("Main thread while it was blocked:\n")
                frames.forEach { append("    at ").append(it).append('\n') }
                append(recentPluginLog(pluginId))
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
        return DevPlugin(manifest, PluginOrigin(SOURCE_ID, info.packageName ?: apk.name), apk, programLauncher(appContext), javaClass.classLoader)
    }

    private fun reject(reason: String): RejectedPlugin {
        log.warn("cannot load the sandbox plugin from $apk: $reason")
        return RejectedPlugin(PluginOrigin(SOURCE_ID, apk.name), reason, apk.name)
    }

    private class DevPlugin(
        override val manifest: PluginManifest,
        override val origin: PluginOrigin,
        private val apk: File,
        private val launcher: File?,
        private val parent: ClassLoader?,
    ) : DiscoveredPlugin {
        override fun classLoader(): ClassLoader =
            PathClassLoader(apk.absolutePath, nativeLibraryDir?.toString(), parent)

        /** The APK's `lib/<abi>` for this device, unpacked next to it, its programs runnable ([unpackNativeLibs]). */
        override val nativeLibraryDir: Path? by lazy { unpackNativeLibs(apk, launcher) }
    }

    companion object {
        const val SOURCE_ID = "sandbox"
        private val log = Log.logger("PluginSandbox")

        /** Where [unpackNativeLibs] keeps the real files of the programs its symlinks start. */
        internal const val PROGRAMS_DIR = ".programs"

        /**
         * The IDE's own installed `libsandboxexec.so` (tools/sandbox-exec), which starts a plugin's program through
         * the system linker; null before Android 10, where a program runs from the app's storage as it is.
         */
        internal fun programLauncher(context: Context): File? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return File(context.applicationInfo.nativeLibraryDir ?: return null, "libsandboxexec.so").takeIf { it.isFile }
        }

        /**
         * Unpacks [apk]'s `lib/<abi>` for this device next to it, as an install would have extracted it.
         *
         * A JNI library only needs to be there. A program does not run from there on Android 10+ (`exec` of a file
         * in the app's own storage is refused with error=13), and the plugin starts it by that path. So with a
         * [launcher], each program the system linker can load moves to [PROGRAMS_DIR] and its name becomes a
         * symlink to the launcher, which may be exec'd (it is in the IDE's installed library directory) and runs
         * `/system/bin/linker64 <PROGRAMS_DIR>/<name> <args>`: the way the NDK plugin's Run starts a user's
         * program. Nothing is installed. A static program stays as it is: no linker can load it.
         */
        internal fun unpackNativeLibs(apk: File, launcher: File?): Path? = runCatching {
            val out = File(apk.parentFile, apk.nameWithoutExtension + "-lib")
            ZipFile(apk).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> zip.entries().asSequence().any { it.name.startsWith("lib/$abi/") } }
                    ?: return@runCatching null
                // Fresh each time: a symlink is not replaced by writing through it (that would write the launcher).
                out.deleteRecursively()
                out.mkdirs()
                for (e in zip.entries().asSequence().filter { it.name.startsWith("lib/$abi/") && !it.isDirectory }) {
                    val name = e.name.substringAfterLast('/')
                    val target = File(out, name)
                    zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                    if (launcher != null && target.inputStream().use { PluginExecutables.isLinkerLoadable(PluginExecutables.head(it)) }) {
                        val programs = File(out, PROGRAMS_DIR).apply { mkdirs() }
                        Files.move(target.toPath(), File(programs, name).toPath())
                        Files.createSymbolicLink(target.toPath(), launcher.toPath())
                    } else {
                        target.setExecutable(true, true)
                    }
                }
            }
            out.toPath().takeIf { Files.isDirectory(it) }
        }.onFailure { log.warn("could not unpack the native libraries of $apk", it) }.getOrNull()
    }
}

/**
 * The real IDE's side: hand a plugin build to a fresh sandbox, and relay the sandbox's crashes and its plugin's
 * log back.
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

    /** How far into the sandbox's [SandboxProcess.logFile] the real IDE has read. Starts at the end: a log
     *  left by an earlier session was already shown then. */
    private var logOffset = SandboxProcess.logFile(appContext).length()
    private val logLock = Any()

    // ONE observer for the directory, held for the IDE's lifetime (a collected FileObserver stops observing).
    // One because before API 29 two observers of the same path in a process share an inotify watch, and the
    // later one's start silently replaces the earlier one's.
    private val stateWatch = object : FileObserver(SandboxProcess.stateDir(appContext).absolutePath, MOVED_TO or CLOSE_WRITE or MODIFY or CREATE) {
        override fun onEvent(event: Int, path: String?) {
            when (path) {
                SandboxProcess.LOG_FILE -> readNewLogLines()
                SandboxProcess.CRASH_FILE -> if ((event and (MOVED_TO or CLOSE_WRITE)) != 0) relayCrash()
            }
        }
    }.also { it.startWatching() }

    private fun relayCrash() {
        // The plugin's last lines first: the crash report ends the sandbox, and anything it logged on the way
        // down belongs before the dialog.
        readNewLogLines()
        val file = SandboxProcess.crashFile(appContext)
        val text = runCatching { file.readText() }.getOrNull() ?: return
        file.delete()
        runCatching { onSandboxCrash(text) }
    }

    /** Replays the lines appended since the last read into this process's log, under a `sandbox:` source. */
    private fun readNewLogLines() = synchronized(logLock) {
        runCatching {
            val file = SandboxProcess.logFile(appContext)
            val length = file.length()
            if (length < logOffset) logOffset = 0 // a new sandbox started the file over
            if (length == logOffset) return@runCatching
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(logOffset)
                val bytes = ByteArray((length - logOffset).toInt())
                raf.readFully(bytes)
                // Only whole lines: the sandbox may be mid-write on the last one.
                val end = bytes.lastIndexOf('\n'.code.toByte())
                if (end < 0) return@runCatching
                logOffset += end + 1
                String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { SandboxLogLine.replay(it) }
            }
        }
    }

    /**
     * Says up front when [apk] packages programs the sandbox cannot start. On Android 10+ a program in the app's
     * own storage runs only through the system linker ([DevApkPluginSource.unpackNativeLibs]), which cannot load
     * a static one; installed, the plugin runs those. The sandbox deliberately installs nothing on the plugin's
     * behalf (Play rejected the IDE for doing so).
     */
    private fun warnAboutPrograms(apk: File, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val scan = runCatching { PluginExecutables.scan(apk.toPath(), Build.SUPPORTED_ABIS.toList()) }.getOrNull() ?: return
        val stuck = if (DevApkPluginSource.programLauncher(appContext) == null) scan.executables else scan.static
        if (stuck.isEmpty()) return
        val names = stuck.joinToString { it.substringAfterLast('/') }
        log("The plugin packages statically linked programs ($names). Android runs those only from an installed app,")
        log("so in the sandbox starting them fails (error=13). Install the plugin to try them, or build them as PIE.")
    }

    override fun launch(apk: Path, log: (String) -> Unit): Boolean {
        val source = apk.toFile()
        if (!source.isFile) {
            log("No APK at $apk: the build did not produce one.")
            return false
        }
        // Only a plugin build this IDE made is loaded: the path alone proves nothing, since a clone or an import
        // can put any file there, and the sandbox runs the APK's code. Hashes the APK (callers are off main).
        if (!BuiltApks.isRecorded(apk)) {
            log("Not loading ${apk.fileName}: the sandbox runs only plugin builds CodeAssist made. Build the plugin again.")
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
        val manifest = runCatching { DevApkPluginSource(appContext, copy).manifest() }.getOrNull()
        val name = manifest?.name
        warnAboutPrograms(copy, log)
        // A sandbox that is already up takes the new build in place: no new process, no app start, and the
        // project and screen it was on stay where they were.
        if (sandboxPid() != null && hotReload(copy, name, log)) {
            SandboxProcess.setCurrent(appContext, copy)
            deleteOtherBuilds(dir, copy)
            return true
        }
        stopRunningSandbox()
        deleteOtherBuilds(dir, copy)
        // ART's compiled code for those builds, which it keeps beside them and never removes by itself.
        File(dir, "oat").deleteRecursively()
        SandboxProcess.crashFile(appContext).delete()
        synchronized(logLock) {
            SandboxProcess.logFile(appContext).delete()
            logOffset = 0
        }
        SandboxProcess.setCurrent(appContext, copy)
        appContext.startActivity(
            Intent(appContext, SandboxActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        log("Started the plugin sandbox with ${name ?: "the plugin"} (${copy.length() / 1024} KB).")
        log("")
        log("It is a separate copy of CodeAssist with its own projects and settings; this IDE is untouched. If the")
        log("plugin crashes it, the stack trace comes back here. Run again while it is open to reload the plugin in")
        log("place; close it from Recents for a fresh start.")
        manifest?.id?.let { log("What it logs shows in this IDE's Logs viewer under ${PluginSandbox.LOG_SOURCE_PREFIX}$it.") }
        return true
    }

    /**
     * Ask the running sandbox to swap in [copy]. The sandbox is brought to the front FIRST: in the background
     * Android may have frozen it, and a frozen process reads no request; the user is about to look at it anyway.
     * Returns false (having said why) when it could not, so the caller starts it fresh instead.
     */
    private fun hotReload(copy: File, name: String?, log: (String) -> Unit): Boolean {
        val dir = SandboxProcess.stateDir(appContext)
        val result = SandboxProcess.reloadResultFile(appContext)
        result.delete()
        val started = System.currentTimeMillis()
        runCatching {
            val tmp = File(dir, "${SandboxProcess.RELOAD_REQUEST}.tmp")
            tmp.writeText(copy.absolutePath)
            tmp.renameTo(File(dir, SandboxProcess.RELOAD_REQUEST))
        }.onFailure {
            log("Could not hand the new build to the running sandbox (${it.message}); starting it again.")
            return false
        }
        runCatching {
            appContext.startActivity(Intent(appContext, SandboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        while (System.currentTimeMillis() - started < RELOAD_TIMEOUT_MS) {
            if (result.isFile) {
                val answer = runCatching { SandboxProcess.ReloadAnswer.decode(result.readText()) }.getOrNull()
                result.delete()
                if (answer == null) break
                val ms = System.currentTimeMillis() - started
                if (answer.ok) {
                    log("Reloaded ${name ?: "the plugin"} in the running sandbox in $ms ms.")
                    if (answer.message.isNotEmpty()) log(answer.message)
                    log("Close the sandbox from Recents for a fresh start, for instance to rerun what an open project")
                    log("took from the plugin when it opened.")
                    return true
                }
                log("The running sandbox could not reload the plugin (${answer.message}); starting it again.")
                return false
            }
            if (sandboxPid() == null) {
                log("The sandbox closed while reloading; starting it again.")
                return false
            }
            Thread.sleep(100)
        }
        log("The running sandbox did not answer; starting it again.")
        return false
    }

    /** Older builds and their unpacked libraries. A sandbox still running one keeps it open, so removing the
     *  file does not pull it out from under it. */
    private fun deleteOtherBuilds(dir: File, copy: File) {
        dir.listFiles().orEmpty()
            .filter { it != copy && (it.name.startsWith("plugin-")) && !it.name.startsWith(copy.nameWithoutExtension) }
            .forEach { it.deleteRecursively() }
    }

    companion object {
        /** The UI process's instance, for the build daemon's hand-off (RemoteBuildRunner) to start it. */
        @Volatile
        var current: AndroidPluginSandbox? = null
            private set

        /** How long the host waits for a running sandbox to answer a reload before starting it fresh. */
        private const val RELOAD_TIMEOUT_MS = 20_000L
    }

    /** The running `:sandbox` process's pid, or null when there is none. */
    private fun sandboxPid(): Int? {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
            .firstOrNull { it.processName == appContext.packageName + SandboxProcess.PROCESS_SUFFIX }?.pid
    }

    /** Kill the `:sandbox` process if one runs, so the next start is a fresh process with the new build. */
    private fun stopRunningSandbox() {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val pid = sandboxPid() ?: return
        Process.killProcess(pid)
        // Give the system a moment to reap it, so the start below does not land in the dying process.
        repeat(20) {
            val alive = runCatching { am.runningAppProcesses }.getOrNull().orEmpty().any { it.pid == pid }
            if (!alive) return
            Thread.sleep(50)
        }
    }
}

/** A throwable as one line for the reload answer. */
private object PluginLoadFailureText {
    fun of(t: Throwable): String = t.message?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.name
}
