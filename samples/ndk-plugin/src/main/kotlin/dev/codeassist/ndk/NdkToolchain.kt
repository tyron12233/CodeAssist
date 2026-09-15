package dev.codeassist.ndk

import dev.ide.platform.log.Logger
import dev.ide.platform.notify.ProgressHandle
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The clang and lld this plugin ships, and how to run them.
 *
 * The toolchain arrives in two halves, for a reason that is not cosmetic. Since Android 10 an app may not
 * `exec()` a file it wrote into its own storage, so anything we RUN has to come out of the installed
 * package's own library directory, which the installer fills and nothing can write to. Anything we only READ
 * has no such restriction, and headers and link libraries are far too many files to package that way, so
 * they ride along as one archive and are unpacked into [PluginRegistration.dataDir] on first use.
 *
 *  - executables: `lib*.so` in the APK's `jniLibs`, found through [PluginRegistration.nativeLibrary]
 *  - headers and link libraries: `assets/toolchain.zip`, unpacked to `<dataDir>/toolchain`
 *
 * Every member answers null or [Status.Unavailable] rather than throwing when the device has no toolchain:
 * this plugin installs on any device, and an ABI it was not built for is an ordinary state to be in, not a
 * broken install.
 */
class NdkToolchain(
    private val reg: PluginRegistration,
    private val log: Logger,
    private val messages: UserMessages?,
) {

    /** The multicall driver: `clang`, `ar`, `nm`, `strip`, … all selected by the first argument. */
    private val driver: Path? = reg.nativeLibrary(DRIVER_LIB)

    /**
     * The linker, as a second copy of the same binary under a name that selects it.
     *
     * We never run this one ourselves; clang does, through `--ld-path`. That is the whole reason it needs a
     * name of its own: there is no way to make clang pass the linker a `-flavor gnu` first argument, so the
     * flavor has to come from the file name. See tools/ndk-toolchain/README.md.
     */
    private val linker: Path? = reg.nativeLibrary(LINKER_LIB)

    /** Where the unpacked headers and link libraries live. */
    private val root: Path = reg.dataDir.resolve("toolchain")

    /** Whether this device can compile at all, and why not when it cannot. */
    sealed interface Status {
        data class Ready(val version: String) : Status
        data class Unavailable(val reason: String) : Status
    }

    @Volatile
    private var unpacked = false

    /**
     * Make the toolchain usable, unpacking the read-only half if this is the first run, and report what the
     * compiler says about itself.
     *
     * Safe to call repeatedly and from anywhere: the unpack happens once, and everything after it is a
     * process launch.
     */
    @Synchronized
    fun prepare(): Status {
        val driver = driver ?: return Status.Unavailable(
            "This plugin ships no compiler for this device's ABI (${System.getProperty("os.arch")})."
        )
        if (!unpacked) {
            val result = runCatching { unpackAssets() }
            result.exceptionOrNull()?.let {
                log.warn("could not unpack the toolchain", it)
                return Status.Unavailable("The toolchain could not be unpacked: ${it.message}")
            }
            unpacked = true
        }
        val version = run(listOf(driver.toString(), "clang", "--version"))
        return if (version.ok) Status.Ready(version.output.lineSequence().firstOrNull().orEmpty())
        else Status.Unavailable("The compiler did not run: ${version.output.take(200)}")
    }

    /**
     * Unpack `assets/toolchain.zip` into [root], once per version.
     *
     * Read through the classloader rather than an AssetManager, because a plugin has no `Context` for its own
     * package. That works because the classloader's path IS the installed APK, so an asset is reachable as an
     * ordinary zip entry. It also has to be ONE entry: a classloader can open a name but cannot list a
     * directory, so ~2800 loose files would be unreachable for want of a way to enumerate them.
     */
    private fun unpackAssets() {
        val stamp = root.resolve(".version")
        val current = layoutStamp()
        if (stamp.exists() && runCatching { stamp.readText().trim() }.getOrNull() == current) return

        val stream: InputStream = javaClass.classLoader?.getResourceAsStream(ASSET_ARCHIVE)
            ?: throw IllegalStateException("$ASSET_ARCHIVE is not packaged in this plugin")

        val progress = messages?.startProgress("Unpacking the NDK toolchain")
        try {
            // A partial unpack from an interrupted first run must not read as a complete one; the stamp is
            // written last, and a previous attempt's tree is dropped rather than merged into.
            if (root.exists()) root.toFile().deleteRecursively()
            Files.createDirectories(root)

            var files = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = root.resolve(entry.name).normalize()
                    // An archive we built ourselves, but unpacking is where a crafted path would escape, and
                    // the check costs nothing.
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
            stamp.writeText(current)
            log.info("unpacked $files toolchain files into $root")
        } finally {
            progress?.finish()
        }
    }

    /**
     * The packaged `android_native_app_glue`, or null when the toolchain has not been unpacked.
     *
     * Source, not a library: the NDK ships the glue to be compiled into each app, so a NativeActivity build
     * compiles `android_native_app_glue.c` alongside the module's own sources and puts this directory on the
     * include path. There is nothing to link and nothing to find at runtime.
     */
    val nativeAppGlueDir: Path?
        get() = root.resolve("native_app_glue").takeIf { Files.isDirectory(it) }

    /**
     * What the unpacked tree was unpacked FROM, so an updated plugin re-unpacks.
     *
     * A hand-bumped constant is the wrong key and was wrong here: the archive gained the NativeActivity glue
     * and every device that had already unpacked kept a tree without it, which surfaced as
     * `'android_native_app_glue.h' file not found` on a project that was correct in every other respect. The
     * driver is replaced by the package manager on every install, so its size and timestamp identify the
     * package this tree came out of; the constant stays in front of it for a layout change the same APK
     * could introduce.
     */
    private fun layoutStamp(): String {
        val d = driver ?: return LAYOUT_VERSION
        val identity = runCatching {
            "${Files.size(d)}-${Files.getLastModifiedTime(d).toMillis()}"
        }.getOrDefault("unknown")
        return "$LAYOUT_VERSION-$identity"
    }

    /** What a tool printed, and whether it succeeded. */
    data class ToolResult(val ok: Boolean, val output: String)

    /**
     * Compile [source] to [output]. [cpp] selects the C++ driver, which is `--driver-mode=g++` rather than a
     * `clang++` of its own, because an APK carries no symlink to make one.
     */
    fun compile(source: Path, output: Path, cpp: Boolean, extraFlags: List<String> = emptyList()): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                add("-c"); add(source.toString())
                add("-o"); add(output.toString())
            }
        )
    }

    /**
     * Check the buffer [text] for errors without producing anything, as if it were the file at [source].
     *
     * This is the plugin's whole diagnostics story, and the reason it needs no language server: the compiler
     * that will build the file is the one that reports on it, so what the editor underlines and what the
     * build fails on cannot disagree.
     *
     * The buffer goes in through **stdin**, not a temporary file, so what is checked is what the user is
     * looking at rather than the last thing they saved. That costs the file's identity, which two things
     * restore: the process runs in the file's own directory, so `#include "sibling.h"` resolves the way it
     * will in the build, and clang reports positions against `<stdin>`, which the caller maps back.
     */
    fun syntaxCheck(
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                add("-fsyntax-only")
                // Positions only, in a form worth parsing: no caret art, no colour escapes, and explicit
                // source ranges so a diagnostic underlines the construct rather than a single character.
                add("-fno-caret-diagnostics")
                add("-fno-color-diagnostics")
                add("-fdiagnostics-print-source-range-info")
                // Read the buffer from stdin, told explicitly which language it is: with no file name there
                // is no extension for clang to infer from.
                add("-x"); add(languageFor(source, cpp))
                add("-")
            },
            stdin = text,
            workingDir = source.parent,
        )
    }

    /**
     * Ask clang what could be typed at [line]:[column] (both 1-based) in the buffer [text].
     *
     * The same compiler, the same flags and the same include path as the build, so what the popup offers is
     * what will actually compile. It is a whole parse per request, which is why the caller debounces and
     * caches rather than asking on every keystroke.
     */
    fun complete(
        source: Path,
        text: String,
        line: Int,
        column: Int,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                add("-fsyntax-only")
                // The position is named against `-`, the same name the buffer arrives under on stdin.
                add("-Xclang"); add("-code-completion-at=-:$line:$column")
                // Macros are most of what a C header offers, and leaving them out would make completion in C
                // look broken. Brief comments give the popup a documentation line for free.
                add("-Xclang"); add("-code-completion-macros")
                add("-Xclang"); add("-code-completion-brief-comments")
                add("-x"); add(languageFor(source, cpp))
                add("-")
            },
            // Shorter than a build: a popup that arrives after this long has already been given up on.
            timeoutSeconds = 20,
            stdin = text,
            workingDir = source.parent,
        )
    }

    /** Link [objects] into a shared library at [output]. */
    fun linkShared(objects: List<Path>, output: Path, libs: List<String> = emptyList()): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        val linker = linker ?: return ToolResult(false, "no linker for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                addAll(commonFlags())
                add("-shared")
                // 16 KB pages. Android 15 runs a library whose LOAD segments are 4 KB-aligned in a
                // compatibility mode and says so in a dialog on first launch, and Play requires alignment
                // outright for apps targeting API 35+. The NDK's own toolchain passes this by default and
                // ours has no default to inherit, so a library built here would ship misaligned forever.
                addAll(PAGE_SIZE_FLAGS)
                // --ld-path, not -fuse-ld=lld: there is no `ld.lld` on any PATH here, only the file we ship.
                add("--ld-path=$linker")
                objects.forEach { add(it.toString()) }
                libs.forEach { add("-l$it") }
                add("-o"); add(output.toString())
            }
        )
    }

    /**
     * What to tell clang the buffer is.
     *
     * A header has to be compiled AS a header (`c++-header`), not as a main file. Given `c++`, clang treats
     * it as a translation unit and reports the diagnostics that only make sense for one — every header
     * opened in the editor draws `#pragma once in main file`, which is both wrong and unfixable by the user.
     */
    private fun languageFor(source: Path, cpp: Boolean): String {
        val header = HEADER_EXTENSIONS.any { source.fileName.toString().endsWith(it) }
        return when {
            cpp && header -> "c++-header"
            cpp -> "c++"
            header -> "c-header"
            else -> "c"
        }
    }

    /**
     * The flags every invocation needs.
     *
     * clang works out where its own headers live from the path of the running executable. That is wrong here:
     * the executable sits in a flat directory of `lib*.so` files rather than in a `bin/` beside a `lib/`, so
     * both locations are named explicitly instead. Leaving either out produces a compiler that cannot find
     * `stddef.h`, which reads like a broken install rather than a missing flag.
     */
    private fun commonFlags(): List<String> = listOf(
        "--target=$TARGET_TRIPLE",
        "-resource-dir", root.resolve("lib-clang").resolve(LLVM_MAJOR).toString(),
        "--sysroot", root.resolve("sysroot").toString(),
    )

    private fun run(
        command: List<String>,
        timeoutSeconds: Long = 120,
        stdin: String? = null,
        workingDir: Path? = null,
    ): ToolResult {
        val libDir = driver?.parent ?: return ToolResult(false, "no toolchain")
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    workingDir?.let { if (Files.isDirectory(it)) directory(it.toFile()) }
                    // The child is a child of the IDE's process, so it does not inherit the plugin package's
                    // own library search path; without this it cannot find libLLVM.so beside it.
                    environment()["LD_LIBRARY_PATH"] = libDir.toString()
                }
                .start()

            // Write the buffer and CLOSE, before reading a byte back. The other order deadlocks as soon as
            // the input is larger than the pipe buffer: the compiler blocks writing diagnostics that nobody
            // is draining, while we block writing source that it is not reading.
            if (stdin != null) {
                runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } }
            } else {
                runCatching { process.outputStream.close() }
            }

            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ToolResult(false, "timed out after ${timeoutSeconds}s\n$output")
            }
            ToolResult(process.exitValue() == 0, output.trim())
        } catch (t: Throwable) {
            log.warn("could not run ${command.firstOrNull()}", t)
            ToolResult(false, t.message ?: t.toString())
        }
    }

    companion object {
        /** `libllvmtools.so`: clang, ar, nm, strip and the rest, selected by the first argument. */
        const val DRIVER_LIB = "llvmtools"

        /** `libld-gnu-lld.so`: the same binary under a name that reaches lld and tells it to be GNU-flavored. */
        const val LINKER_LIB = "ld-gnu-lld"

        const val ASSET_ARCHIVE = "assets/toolchain.zip"
        const val LLVM_MAJOR = "18"
        const val TARGET_TRIPLE = "aarch64-linux-android26"

        /** Bump when the packaged asset layout changes, so an older unpacked tree is replaced rather than reused. */
        const val LAYOUT_VERSION = "1"

        /**
         * What every shared library this plugin links is aligned to.
         *
         * 16 KB, not the linker's 4 KB default: a device with 16 KB pages (Android 15 and up) cannot map a
         * 4 KB-aligned segment directly, so it either refuses the library or runs the app in a compatibility
         * mode it warns the user about. Both values are set because they answer different questions --
         * max-page-size is the alignment of the segments in the file, common-page-size what the linker
         * assumes it can share.
         */
        val PAGE_SIZE_FLAGS = listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")

        private val HEADER_EXTENSIONS = listOf(".h", ".hpp", ".hh", ".hxx", ".inl")
    }
}
