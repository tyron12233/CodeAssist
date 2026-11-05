package dev.codeassist.dart

import dev.ide.platform.log.Logger
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * The Dart SDK this plugin carries, and the layout it is run from.
 *
 * It arrives in two halves (see tools/dart-sdk/fetch-dart-android.sh). What is executed, the runtimes and
 * the AOT snapshots, is in this package's native library directory, the one place Android lets an app run a
 * file from. What is only read, the SDK's libraries, is `assets/dart-sdk.zip`, unpacked here on first use.
 *
 * The unpacked SDK lives in the IDE's INTERNAL storage, [PluginRegistration.privateDataDir], not
 * [PluginRegistration.dataDir]. `dart pub` finds its SDK by walking up from the path it was started by, so
 * [launcher] is a symlink at `<sdk>/bin/dart` pointing at the runtime in the native library directory, and
 * shared storage cannot hold a symlink. Being the plugin's own directory, the IDE offers to delete it once the
 * plugin is uninstalled; the path this plugin picked for itself before (`files/dart-plugin`) outlived it.
 *
 * Safe to use from several processes at once: the IDE's (the language server) and `:build`'s (Run) both
 * prepare it, and a file lock makes one of them unpack while the other waits.
 */
class DartSdk(
    private val reg: PluginRegistration,
    private val log: Logger,
    private val messages: UserMessages?,
) {
    sealed interface Status {
        data class Ready(val version: String) : Status
        data class Unavailable(val reason: String) : Status
    }

    /**
     * Where everything this plugin writes for itself lives: the SDK, the pub cache, a home and a temp dir. The
     * plugin's [PluginRegistration.privateDataDir]; on a host older than SPI 3.4.0, which has none, the path
     * this plugin used before it existed.
     */
    val home: Path by lazy { privateHome() ?: internalHome() ?: reg.dataDir }

    private fun privateHome(): Path? {
        // NoSuchMethodError (or AbstractMethodError) on a host from before the property.
        val dir = try { reg.privateDataDir } catch (e: LinkageError) { return null }
        internalHome()?.let { legacy ->
            runCatching { adoptLegacyHome(legacy, dir) }
                .onFailure { log.warn("could not move the Dart SDK from $legacy to $dir; it stays there", it) }
        }
        return dir
    }

    /**
     * Moves [legacy], where an earlier version of this plugin kept everything, into [dir], once, and leaves a link
     * in its place. The link matters: a project's `.dart_tool/package_config.json` names its packages by their
     * absolute path in the pub cache, so the old path has to keep resolving until the next `pub get` rewrites it.
     * A rename within one file system, so it is instant whatever the pub cache holds. Under a lock, since the IDE
     * and `:build` may both get here first; whichever is second finds the link and does nothing.
     */
    private fun adoptLegacyHome(legacy: Path, dir: Path) {
        if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return
        val root = dir.parent ?: return
        withFileLock(root.resolve(".dart-plugin-move.lock")) {
            if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return@withFileLock
            // Something already here means the move happened another way; the old copy is left for the IDE to
            // offer rather than merged into a directory that is in use.
            val empty = Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
            if (!empty) return@withFileLock
            Files.delete(dir)
            Files.move(legacy, dir, StandardCopyOption.ATOMIC_MOVE)
            Files.createSymbolicLink(legacy, dir)
            log.info("moved the Dart SDK and pub cache from $legacy to $dir")
        }
    }

    val sdkDir: Path get() = home.resolve("sdk")
    val pubCache: Path get() = home.resolve("pub-cache")

    private fun native(name: String): Path? = reg.nativeLibrary(name)?.takeIf { Files.isRegularFile(it) }

    /** Runs the AOT snapshots: the analysis server, the kernel compiler, `dart` itself. */
    val runtime: Path? get() = native("dartaotruntime")

    /** The JIT VM a program runs in. */
    val vm: Path? get() = native("dartvm")
    val analysisServer: Path? get() = native("analysis_server")
    val genKernel: Path? get() = native("gen_kernel")
    val dartdev: Path? get() = native("dartdev")

    /** The incremental compiler behind Flutter's hot reload. */
    val frontendServer: Path? get() = native("frontend_server")

    /** `<sdk>/bin/dart`: the runtime, by a path two levels below the SDK root, which is how `dart` finds it. */
    val launcher: Path get() = sdkDir.resolve("bin").resolve("dart")

    /** The core libraries in kernel form, which the kernel compiler links against. */
    val platformDill: Path get() = sdkDir.resolve("lib/_internal/vm_platform_strong.dill")

    @Volatile
    private var ready: Status.Ready? = null

    /**
     * Make the SDK usable: unpack the libraries if this version has not been, and point the launcher links
     * at this install's native library directory (which moves with every update of the plugin). Cheap after
     * the first call in a process.
     */
    @Synchronized
    fun prepare(): Status {
        ready?.let { if (linksCurrent()) return it }
        val runtime = runtime ?: return Status.Unavailable(NO_SDK)
        if (vm == null || analysisServer == null || genKernel == null || dartdev == null) return Status.Unavailable(NO_SDK)
        return try {
            Files.createDirectories(home)
            withFileLock(home.resolve(".lock")) {
                unpackIfNeeded()
                link(launcher, runtime)
                link(sdkDir.resolve("bin/snapshots/dartdev_aot.dart.snapshot"), dartdev!!)
            }
            for (dir in listOf("home", "tmp")) Files.createDirectories(home.resolve(dir))
            Files.createDirectories(pubCache)
            Status.Ready("Dart $VERSION").also { ready = it }
        } catch (e: Exception) {
            log.warn("could not prepare the Dart SDK", e)
            Status.Unavailable("The Dart SDK could not be prepared: ${e.message}")
        }
    }

    /** The environment every Dart process gets: a home, a temp dir and a pub cache of the plugin's own. */
    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.resolve("home").toString(),
        "TMPDIR" to home.resolve("tmp").toString(),
        "PUB_CACHE" to pubCache.toString(),
        // pub's user agent names the client it runs under.
        "PUB_ENVIRONMENT" to "codeassist",
        // A program's output goes to a console that shows text, not a terminal that draws escape codes.
        "TERM" to "dumb",
    )

    private fun linksCurrent(): Boolean {
        val runtime = runtime ?: return false
        return runCatching { Files.readSymbolicLink(launcher) == runtime }.getOrDefault(false)
    }

    private fun unpackIfNeeded() {
        val stamp = sdkDir.resolve(".unpacked")
        if (Files.isRegularFile(stamp) && runCatching { String(Files.readAllBytes(stamp)).trim() }.getOrNull() == VERSION) return
        val stream: InputStream = javaClass.classLoader?.getResourceAsStream(ASSET)
            ?: throw IllegalStateException("$ASSET is not packaged in this plugin")
        val progress = messages?.startProgress("Preparing the Dart SDK")
        val started = System.nanoTime()
        try {
            // A partial unpack from an interrupted run must not read as complete: the stamp is written last, and
            // a previous attempt is dropped rather than merged into.
            if (Files.exists(sdkDir)) sdkDir.toFile().deleteRecursively()
            Files.createDirectories(sdkDir)
            var files = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = sdkDir.resolve(entry.name).normalize()
                    require(target.startsWith(sdkDir)) { "entry escapes the SDK directory: ${entry.name}" }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        files++
                        if (files % 100 == 0) progress?.detail = entry.name.substringAfterLast('/')
                    }
                    zip.closeEntry()
                }
            }
            Files.write(stamp, VERSION.toByteArray())
            log.info("unpacked $files SDK files in ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            progress?.finish()
        }
    }

    /** Make [at] a symlink to [target], replacing whatever is there unless it already is exactly that. */
    private fun link(at: Path, target: Path) {
        if (runCatching { Files.readSymbolicLink(at) == target }.getOrDefault(false)) return
        Files.createDirectories(at.parent)
        Files.deleteIfExists(at)
        Files.createSymbolicLink(at, target)
    }

    private inline fun <T> withFileLock(file: Path, block: () -> T): T =
        RandomAccessFile(file.toFile(), "rw").use { raf -> raf.channel.lock().use { block() } }

    /**
     * `<app data>/files/dart-plugin`. ART points `java.io.tmpdir` at the app's own cache directory in every
     * process of the app, so its parent is the app's data directory. Null off Android (or anywhere that is not
     * internal storage), where the plugin's own data directory serves.
     */
    private fun internalHome(): Path? {
        val tmp = System.getProperty("java.io.tmpdir")?.takeIf { it.startsWith("/data/") } ?: return null
        val appData = Paths.get(tmp).parent ?: return null
        return appData.resolve("files").resolve("dart-plugin").takeIf { Files.isDirectory(appData, LinkOption.NOFOLLOW_LINKS) }
    }

    companion object {
        /**
         * Dart VM flags for the long-running tools (the analysis server, the Flutter compiler): a heap that grows more
         * gently, is compacted, gives freed pages back, and is collected while the tool waits for its next request.
         * Measured on the device with a Flutter app open, 30 edits and a completion: the analysis server settles at
         * ~250 MB instead of ~320 MB (peak ~320 instead of ~340), the compiler at ~350 instead of ~375; request times
         * unchanged. A capped heap (`--old_gen_heap_size`) is NOT among them: the compiler crashed at 256 MB.
         *
         * The VM refuses to start on a flag it does not know, so these are tied to the SDK this plugin carries.
         */
        val VM_TUNING = listOf(
            "--old_gen_growth_space_ratio=10",
            "--old_gen_growth_rate=60",
            "--use_compactor",
            "--dontneed_on_sweep",
            "--mark_when_idle",
            "--idle_timeout_micros=200000",
            "--idle_duration_micros=200000",
        )

        /** The SDK version staged by tools/dart-sdk; also the unpack stamp, so a new SDK unpacks afresh. */
        const val VERSION = "3.13.7"
        private const val ASSET = "assets/dart-sdk.zip"
        const val NO_SDK = "This plugin has no Dart SDK for this device's ABI. Install the plugin build made for it (arm64-v8a, armeabi-v7a or x86_64; there is none for x86)."
    }
}
