package dev.codeassist.swift

import dev.ide.platform.log.Logger
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.OverlappingFileLockException
import java.util.zip.ZipInputStream

/**
 * The Swift toolchain this plugin carries, and the layout it runs from.
 *
 * It arrives in two halves (see tools/swift-sdk/stage_swift.py). What is executed or mapped as code, the compiler
 * (`libswiftfrontend.so`) and every library it, the Swift runtime and Foundation load, is in this package's native
 * library directory, the one place Android lets an app run a file from; the libraries find each other there through
 * their `$ORIGIN` run path. What is only read, the standard library's modules and the C headers, is
 * `assets/swift-toolchain.zip`, unpacked into the plugin's private directory on first use.
 *
 * A program runs in the compiler's JIT (`-interpret`): no linker is involved, so this needs nothing from the NDK
 * plugin.
 *
 * Safe to use from several processes at once (the IDE's and `:build`'s): a file lock makes one of them unpack while
 * the other waits.
 */
class SwiftToolchain(
    private val reg: PluginRegistration,
    private val log: Logger,
    private val messages: UserMessages?,
) {
    sealed interface Status {
        data class Ready(val version: String) : Status
        data class Unavailable(val reason: String) : Status
    }

    val home: Path by lazy { reg.privateDataDir }
    private val root: Path get() = home.resolve("toolchain")

    /** The `-sdk` root: `usr/include` (the C headers) and `usr/lib/swift` (the resource directory) are under it. */
    val sdkRoot: Path get() = root
    val resourceDir: Path get() = root.resolve("usr/lib/swift")
    val moduleCache: Path get() = home.resolve("module-cache")

    /**
     * CodeAssist's SwiftUI engine as a module (`SwiftUI`), beside its library `libSwiftUI.so` in the native library
     * directory: a preview compiles only the user's code against it (tools/swift-sdk/build_swiftui.sh).
     */
    val swiftUIModules: Path get() = root.resolve("swiftui")
    val hasSwiftUI: Boolean get() = reg.nativeLibrary("SwiftUI")?.let { Files.isRegularFile(it) } == true &&
        Files.isRegularFile(swiftUIModules.resolve("SwiftUI.swiftmodule"))

    /** The compiler. Null when this build of the plugin carries no toolchain for the device's ABI. */
    val frontend: Path? get() = reg.nativeLibrary("swiftfrontend")?.takeIf { Files.isRegularFile(it) }
    private val libDir: Path? get() = reg.nativeLibraryDir

    @Volatile
    private var ready: Status.Ready? = null

    /** The target triple of the ABI this build carries, with the API level the plugin's floor allows. */
    @Volatile
    var target: String = ""
        private set

    /** Make the toolchain usable: unpack the read-only half if this version has not been. Cheap after the first call. */
    @Synchronized
    fun prepare(): Status {
        ready?.let { return it }
        if (frontend == null || libDir == null) return Status.Unavailable(NO_TOOLCHAIN)
        return try {
            Files.createDirectories(home)
            withFileLock(home.resolve(".lock")) { unpackIfNeeded() }
            target = String(Files.readAllBytes(root.resolve("TARGET"))).trim() + API_LEVEL
            for (dir in listOf("home", "tmp")) Files.createDirectories(home.resolve(dir))
            Files.createDirectories(moduleCache)
            Status.Ready("Swift $VERSION").also { ready = it }
        } catch (e: Exception) {
            log.warn("could not prepare the Swift toolchain", e)
            Status.Unavailable("The Swift toolchain could not be prepared: ${e.message}")
        }
    }

    /**
     * Remove the lock files clang leaves in the module cache when a compiler is killed mid-build (Stop, or the process
     * dying). The next compiler that needs that module waits on the stale lock for minutes before building it itself:
     * measured on the emulator, a Run sat 8.8 minutes doing nothing. Only safe while no other compiler is running in
     * this cache, which is what [withCompilerLock] arranges.
     */
    private fun clearStaleLocks() {
        if (!Files.isDirectory(moduleCache)) return
        Files.walk(moduleCache).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".lock") }.forEach { runCatching { Files.delete(it) } }
        }
    }

    /**
     * Runs [block], a compiler's whole life, holding the compiler lock SHARED, across processes, so any number of
     * Runs go at once. Before that, when the lock can be had EXCLUSIVELY (no compiler is alive anywhere), the module
     * cache's lock files are leftovers of a killed one and are cleared ([clearStaleLocks]).
     */
    fun <T> withCompilerLock(block: () -> T): T =
        RandomAccessFile(home.resolve(".compiler.lock").toFile(), "rw").use { raf ->
            // A file lock belongs to the process: while another Run in this process holds it, both calls throw
            // OverlappingFileLockException. That Run's lock already covers this one, and a compiler is alive.
            try {
                raf.channel.tryLock()?.use { clearStaleLocks() }
            } catch (_: OverlappingFileLockException) {
            }
            val shared = try {
                raf.channel.lock(0L, Long.MAX_VALUE, true)
            } catch (_: OverlappingFileLockException) {
                null
            }
            try {
                block()
            } finally {
                shared?.release()
            }
        }

    /** `swift-frontend -frontend -interpret`: compiles [files] as module [moduleName] and runs it in the JIT. */
    fun interpretCommand(
        files: List<Path>,
        moduleName: String,
        programArgs: List<String> = emptyList(),
        withSwiftUI: Boolean = false,
    ): List<String> {
        val compiler = frontend ?: error(NO_TOOLCHAIN)
        val libs = libDir ?: error(NO_TOOLCHAIN)
        return buildList {
            add(compiler.toString()); add("-frontend"); add("-interpret")
            files.forEach { add(it.toString()) }
            add("-module-name"); add(moduleName)
            add("-target"); add(target)
            add("-resource-dir"); add(resourceDir.toString())
            add("-sdk"); add(sdkRoot.toString())
            add("-module-cache-path"); add(moduleCache.toString())
            // Where the JIT finds the runtime a program loads (libswiftCore.so, libFoundation.so, ...).
            add("-L"); add(libs.toString())
            if (withSwiftUI) { add("-I"); add(swiftUIModules.toString()) }
            // The diagnostics go to a console that shows text, not a terminal that draws colours.
            add("-no-color-diagnostics")
            if (programArgs.isNotEmpty()) { add("--"); addAll(programArgs) }
        }
    }

    /** sourcekit-lsp, as the APK carries it (bin/sourcekit-lsp, renamed so it is extracted). */
    val languageServer: Path? get() = reg.nativeLibrary("sourcekitlsp")?.takeIf { Files.isRegularFile(it) }

    /**
     * A toolchain directory as sourcekit-lsp looks for one (`usr/bin/swift-frontend`, `usr/lib/libsourcekitdInProc.so`
     * and its plugins, `usr/lib/swift`): the unpacked resource directory, with links to what the APK carries, which has
     * to live in the native library directory to be executed or loaded. Remade on every start, because that directory
     * moves when the plugin is updated.
     */
    fun languageServerToolchain(): Path? {
        val libs = libDir ?: return null
        val usr = root.resolve("usr")
        val links = mapOf(
            "bin/swift-frontend" to "libswiftfrontend.so",
            "bin/swiftc" to "libswiftfrontend.so",
            "bin/sourcekit-lsp" to "libsourcekitlsp.so",
            "lib/libsourcekitdInProc.so" to "libsourcekitdInProc.so",
            "lib/libSwiftSourceKitPlugin.so" to "libSwiftSourceKitPlugin.so",
            "lib/libSwiftSourceKitClientPlugin.so" to "libSwiftSourceKitClientPlugin.so",
        )
        for ((at, lib) in links) {
            val link = usr.resolve(at)
            val target = libs.resolve(lib)
            if (!Files.isRegularFile(target)) continue
            Files.createDirectories(link.parent)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        return root
    }

    /** The compiler arguments of module [moduleName] made of [files], as the language server's database gives them. */
    fun compileArguments(moduleName: String, files: List<Path>): List<String> = buildList {
        add(root.resolve("usr/bin/swiftc").toString())
        add("-module-name"); add(moduleName)
        add("-target"); add(target)
        add("-sdk"); add(sdkRoot.toString())
        add("-resource-dir"); add(resourceDir.toString())
        add("-module-cache-path"); add(moduleCache.toString())
        if (hasSwiftUI) { add("-I"); add(swiftUIModules.toString()) }
        files.forEach { add(it.toString()) }
    }

    /** The environment every Swift process gets: a home and a temp dir of the plugin's own. */
    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.resolve("home").toString(),
        "TMPDIR" to home.resolve("tmp").toString(),
        "TERM" to "dumb",
        // The SwiftUI engine's SF Symbol frames (measured from iOS; tools/swiftui-parity/SymbolProbe.swift).
        "CODEASSIST_SYMBOL_METRICS" to swiftUIModules.resolve("symbol-metrics.tsv").toString(),
    )

    private fun unpackIfNeeded() {
        val stamp = root.resolve(".unpacked")
        // Version, layout and the archive's digest (written by the build): a plugin update that changes anything in
        // the toolchain, the SwiftUI engine included, unpacks afresh.
        val digest = runCatching {
            javaClass.classLoader?.getResourceAsStream(DIGEST_ASSET)?.use { String(it.readBytes()).trim() }
        }.getOrNull().orEmpty()
        val expected = "$STAMP/$digest"
        if (Files.isRegularFile(stamp) && runCatching { String(Files.readAllBytes(stamp)).trim() }.getOrNull() == expected) return
        val stream = javaClass.classLoader?.getResourceAsStream(ASSET)
            ?: throw IllegalStateException("$ASSET is not packaged in this plugin")
        val progress = messages?.startProgress("Preparing the Swift toolchain")
        val started = System.nanoTime()
        try {
            // A partial unpack from an interrupted run must not read as complete: the stamp is written last, and a
            // previous attempt is dropped rather than merged into. The module cache was built from the old modules.
            if (Files.exists(root)) root.toFile().deleteRecursively()
            if (Files.exists(moduleCache)) moduleCache.toFile().deleteRecursively()
            Files.createDirectories(root)
            var files = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = root.resolve(entry.name).normalize()
                    require(target.startsWith(root)) { "entry escapes the toolchain directory: ${entry.name}" }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        files++
                        if (files % 200 == 0) progress?.detail = entry.name.substringAfterLast('/')
                    }
                    zip.closeEntry()
                }
            }
            Files.write(stamp, expected.toByteArray())
            log.info("unpacked $files toolchain files in ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            progress?.finish()
        }
    }

    private inline fun <T> withFileLock(file: Path, block: () -> T): T =
        RandomAccessFile(file.toFile(), "rw").use { raf -> raf.channel.lock().use { block() } }

    companion object {
        /** The Swift version staged by tools/swift-sdk. */
        const val VERSION = "6.4.3"

        /** Bumped whenever the staged layout changes for the same Swift version, so the device unpacks afresh. */
        private const val LAYOUT = 3
        private const val STAMP = "$VERSION/$LAYOUT"

        /** The plugin's minSdk: what a program may assume of the device. */
        private const val API_LEVEL = "26"
        private const val ASSET = "assets/swift-toolchain.zip"
        private const val DIGEST_ASSET = "assets/swift-toolchain.sha256"
        const val NO_TOOLCHAIN = "This build of the Swift plugin has no Swift toolchain for this device's ABI. Install the plugin build made for it (arm64-v8a, armeabi-v7a or x86_64)."
    }
}
