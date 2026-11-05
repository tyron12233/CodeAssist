package dev.codeassist.ndk

import dev.ide.platform.log.Logger
import dev.ide.platform.notify.ProgressHandle
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
     * Safe to call repeatedly and from anywhere: the unpack and the version check happen once, and every call
     * after that answers from memory.
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
        // Asked once per process, not per call. Every completion and every error check calls this first, and
        // launching `clang --version` each time was a second process per keystroke for an answer that cannot
        // change while the IDE runs: an updated plugin is only picked up by a restart. A failure is not kept,
        // so a toolchain that did not run is asked again next time.
        ready?.let { return it }
        val version = run(listOf(driver.toString(), "clang", "--version"))
        return if (version.ok) Status.Ready(version.output.lineSequence().firstOrNull().orEmpty()).also { ready = it }
        else Status.Unavailable("The compiler did not run: ${version.output.take(200)}")
    }

    @Volatile
    private var ready: Status.Ready? = null

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

    /**
     * The shared C++ runtime an app linked with `c++_shared` has to carry in its APK beside its own library.
     * Null before [prepare] has unpacked the toolchain.
     */
    fun sharedCxxRuntime(abi: NdkAbi = hostAbi): Path? =
        root.resolve("sysroot/usr/lib/${abi.libTriple}/libc++_shared.so").takeIf { Files.isRegularFile(it) }

    /**
     * The ABI this device runs, which is the ABI the compiler itself was unpacked for and the only one whose
     * programs can be started here. Editor tooling (errors, completion) checks against it too, since what a
     * file means rarely depends on the target and the device's own is always installed.
     */
    val hostAbi: NdkAbi by lazy {
        NdkAbi.forDevice(runCatching { android.os.Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList()))
    }

    /**
     * What a tool printed, and whether it succeeded. [bufferName] is the name diagnostics in [output] give the
     * checked buffer: `<stdin>` from the command line, the file's own path from the resident daemon.
     */
    data class ToolResult(val ok: Boolean, val output: String, val bufferName: String = "<stdin>")

    /**
     * Compile [source] to [output]. [cpp] selects the C++ driver, which is `--driver-mode=g++` rather than a
     * `clang++` of its own, because an APK carries no symlink to make one.
     *
     * With a [depFile], clang also writes every header the source opened into it (`-MD -MF`), as a by-product
     * of the same compile, which is what lets the next build skip this source until one of them changes.
     */
    fun compile(
        source: Path,
        output: Path,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
        depFile: Path? = null,
        abi: NdkAbi = hostAbi,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags(abi))
                addAll(extraFlags)
                if (depFile != null) { add("-MD"); add("-MF"); add(depFile.toString()) }
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
        cancellation: ToolCancellation? = null,
    ): ToolResult = viaDaemon("check", source, text, cpp, extraFlags, cancellation = cancellation)
        ?: withPreamble(source, text, cpp, extraFlags, usableBefore = text.length, what = "check", cancellation) { driver, preamble ->
        run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                addAll(preambleFlags(preamble))
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
            cancellation = cancellation,
        )
    }

    /**
     * Ask clang what could be typed at [line]:[column] (both 1-based) in the buffer [text]. [offset] is the
     * same position as a char offset, which decides whether the precompiled preamble can be used: not when
     * the position is inside it, as it is while typing an `#include`.
     *
     * The same compiler, the same flags and the same include path as the build, so what the popup offers is
     * what will actually compile.
     */
    fun complete(
        source: Path,
        text: String,
        line: Int,
        column: Int,
        offset: Int,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
        cancellation: ToolCancellation? = null,
    ): ToolResult = viaDaemon("complete", source, text, cpp, extraFlags, line, column, cancellation)
        ?: withPreamble(source, text, cpp, extraFlags, usableBefore = offset, what = "completion", cancellation) { driver, preamble ->
        run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                addAll(preambleFlags(preamble))
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
            cancellation = cancellation,
        )
    }

    /**
     * Classify the identifiers in [text] (see the daemon's `tokens` command and [ClangSemanticTokens]). Only
     * the resident daemon can answer this -- it needs the parsed file it keeps -- so on a toolchain without
     * one, or when it is unavailable, the answer is null and the editor keeps its lexical colours.
     */
    fun semanticTokens(source: Path, text: String, cpp: Boolean, extraFlags: List<String>): ToolResult? =
        viaDaemon("tokens", source, text, cpp, extraFlags, cancellation = null)

    // -----------------------------------------------------------------------------------------------
    // The resident daemon (see ClangDaemon)
    // -----------------------------------------------------------------------------------------------

    /** Null on a toolchain built before the daemon existed: then everything runs clang per request. */
    private val daemon: ClangDaemon? by lazy {
        val executable = reg.nativeLibrary(ClangDaemon.LIB) ?: return@lazy null
        if (!Files.isRegularFile(executable)) return@lazy null
        ClangDaemon(executable, executable.parent, reg.dataDir.resolve("daemon"), log)
    }

    /**
     * Ask the resident daemon, or answer null for the caller to run clang itself.
     *
     * Null covers every way the daemon cannot help: there is none, it died, it timed out, or it could not
     * parse the file at all. The one exception is a request cancelled while it waited its turn, which must
     * not fall through to a launch of its own -- that is answered as cancelled here.
     *
     * The arguments are the command line's minus what libclang supplies itself (`-fsyntax-only`, the input
     * name), with `-x` naming the language because there is no extension on a buffer to infer it from.
     */
    private fun viaDaemon(
        command: String,
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String>,
        line: Int = 0,
        column: Int = 0,
        cancellation: ToolCancellation?,
    ): ToolResult? {
        val daemon = daemon ?: return null
        val started = System.nanoTime()
        val args = buildList {
            addAll(commonFlags())
            addAll(extraFlags)
            // libclang precompiles each file's preamble itself; this makes it instantiate the headers'
            // templates into it once, as PreambleCache does for the command line. Measured on the emulator:
            // a check went 46-56 -> 16-25 ms. Same caveat as there; C has no templates.
            if (cpp) add("-fpch-instantiate-templates")
            add("-x"); add(languageFor(source, cpp))
        }
        val reply = daemon.request(command, source.toString(), text, args, line, column, cancellation = cancellation,
            onTurn = { waited -> if (waited > 5) log.debug("clang $command waited $waited ms for the daemon") })
        if (reply == null) {
            return if (cancellation?.isCancelled == true) ToolResult(false, "cancelled") else null
        }
        if (!reply.ok) {
            log.info("the resident clang could not $command ${source.fileName}: ${reply.payload.take(200)}")
            return null
        }
        log.debug("clang $command: ${(System.nanoTime() - started) / 1_000_000} ms (daemon)")
        return ToolResult(true, reply.payload, bufferName = source.toString())
    }

    // -----------------------------------------------------------------------------------------------
    // The precompiled preamble (see ClangPreamble / PreambleCache)
    // -----------------------------------------------------------------------------------------------

    private val preambles: PreambleCache by lazy {
        PreambleCache(reg.dataDir.resolve("preambles"), log, build = ::buildPreamble)
    }

    /**
     * Run [invoke] with the buffer's precompiled preamble when one is built, and without it otherwise.
     *
     * Without one, the request costs what it always did and a build is queued for next time. With one,
     * clang loads the headers instead of parsing them; if it refuses the file because a header has changed
     * since, the request is re-run without it and the preamble is rebuilt, so a stale cache can cost one
     * extra launch but never a wrong answer.
     */
    private fun withPreamble(
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String>,
        usableBefore: Int,
        what: String,
        cancellation: ToolCancellation?,
        invoke: (driver: Path, preamble: PreambleCache.Ready?) -> ToolResult,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        val started = System.nanoTime()
        fun timed(result: ToolResult, how: String): ToolResult {
            log.debug("clang $what: ${(System.nanoTime() - started) / 1_000_000} ms $how")
            return result
        }

        val end = ClangPreamble.end(text)
        val sourceDir = source.parent
        if (end == 0 || end > usableBefore || sourceDir == null) return timed(invoke(driver, null), "(no preamble)")

        val request = PreambleCache.Request(
            text = text.substring(0, end),
            cpp = cpp,
            flags = extraFlags,
            sourceDir = sourceDir,
            toolchainStamp = layoutStamp(),
        )
        val ready = preambles.readyOrQueue(request, ClangPreamble.utf8Length(text, end))
            ?: return timed(invoke(driver, null), "(preamble queued)")

        val result = invoke(driver, ready)
        // A cancelled run's output is cut short, so it says nothing about whether the preamble is stale.
        if (cancellation?.isCancelled == true) return timed(result, "(cancelled)")
        if (!PreambleCache.isStale(result.output)) return timed(result, "(preamble)")
        log.info("a precompiled preamble went stale: ${result.output.lineSequence().firstOrNull().orEmpty().take(200)}")
        preambles.rebuild(request)
        return timed(invoke(driver, null), "(stale preamble, rebuilt)")
    }

    /** The two flags that load a precompiled preamble and skip the bytes of the buffer it stands for. */
    private fun preambleFlags(preamble: PreambleCache.Ready?): List<String> =
        if (preamble == null) emptyList()
        else listOf("-include-pch", preamble.pch.toString(), "-Xclang", "-preamble-bytes=${preamble.bytes},1")

    /**
     * Compile a preamble, already written to [header], into [pch].
     *
     * As a HEADER of the source's language, with the source's flags and from the source's directory, so it
     * means what those lines mean at the top of the file: relative `-I` flags resolve from the same place, and
     * `-iquote` stands in for the directory a quote-include is looked up in first, which for this file would
     * be the cache directory rather than the source's.
     */
    private fun buildPreamble(request: PreambleCache.Request, header: Path, pch: Path): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (request.cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(request.flags)
                // Instantiate the templates the headers use once, into the .pch, instead of at the end of
                // every check that loads it. Measured on the emulator: an error check on a file including
                // six standard headers went 59 -> 31 ms, on an empty file 52 -> 20 ms, at no cost to the
                // build of the .pch itself. The price is rare: code that explicitly specializes a template
                // AFTER a header already used it can see a false "explicit specialization after
                // instantiation" in the editor. The build does not use the .pch, so it is never affected.
                // C++ only; C has no templates and would warn that the flag is unused.
                if (request.cpp) add("-fpch-instantiate-templates")
                add("-iquote"); add(request.sourceDir.toString())
                add("-x"); add(if (request.cpp) "c++-header" else "c-header")
                add(header.toString())
                add("-o"); add(pch.toString())
            },
            timeoutSeconds = 60,
            workingDir = request.sourceDir,
        )
    }

    /**
     * Link [objects] into a shared library at [output].
     *
     * [cpp] links through the C++ driver, which is what brings the C++ runtime in; [stl] says which one (see
     * [NdkFacet.stl]). Linked through the C driver, C++ code links without error and then fails at
     * `System.loadLibrary` with "cannot locate symbol", because nothing provided `std::` or `operator new`.
     * `--no-undefined` is the guard against that whole class: an unresolved symbol is a link error here, with
     * its name, instead of a crash on the device.
     */
    fun linkShared(
        objects: List<Path>,
        output: Path,
        libs: List<String> = emptyList(),
        cpp: Boolean = false,
        stl: String = "c++_static",
        abi: NdkAbi = hostAbi,
    ): ToolResult = link(objects, output, libs, cpp, stl, shared = true, abi)

    /**
     * Link [objects] into a program at [output], for Run.
     *
     * The same flags as [linkShared] minus `-shared`: Android executables are position-independent by
     * default, which is also what lets one set of `-fPIC` objects serve both the library a build packages
     * and the program Run starts. Aligned to 16 KB like every other output, because Android 15 refuses to
     * map a 4 KB-aligned program at all ("program alignment (4096) cannot be smaller than system page size").
     */
    fun linkExecutable(
        objects: List<Path>,
        output: Path,
        libs: List<String> = emptyList(),
        cpp: Boolean = false,
        stl: String = "c++_static",
        abi: NdkAbi = hostAbi,
    ): ToolResult = link(objects, output, libs, cpp, stl, shared = false, abi)

    private fun link(
        objects: List<Path>,
        output: Path,
        libs: List<String>,
        cpp: Boolean,
        stl: String,
        shared: Boolean,
        abi: NdkAbi,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        val linker = linker ?: return ToolResult(false, "no linker for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags(abi))
                if (shared) add("-shared")
                add("-Wl,--no-undefined")
                if (cpp) when (stl) {
                    "c++_static" -> add("-static-libstdc++")
                    "none" -> add("-nostdlib++")
                    // c++_shared is the driver's own default on Android: it links libc++_shared.so.
                }
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
     * The Run launcher (`libndkrun.so`, tools/ndk-toolchain/daemon/ndkrun.c), which runs a program in a
     * pseudo-terminal. Null on a toolchain built before it existed; Run then uses plain pipes.
     */
    val runLauncher: Path?
        get() = reg.nativeLibrary(RUN_LAUNCHER_LIB)?.takeIf { Files.isRegularFile(it) }

    /** The directory the toolchain's own libraries are in, for a child process's `LD_LIBRARY_PATH`. */
    val libraryDir: Path? get() = driver?.parent

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
    private fun commonFlags(abi: NdkAbi = hostAbi): List<String> = listOf(
        "--target=${abi.target}",
        "-resource-dir", root.resolve("lib-clang").resolve(LLVM_MAJOR).toString(),
        "--sysroot", root.resolve("sysroot").toString(),
    ) + abi.extraFlags

    private fun run(
        command: List<String>,
        timeoutSeconds: Long = 120,
        stdin: String? = null,
        workingDir: Path? = null,
        cancellation: ToolCancellation? = null,
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
            // Registered before anything blocks, so a request cancelled from here on stops the compiler.
            cancellation?.attach(process)

            // The timeout is a watchdog that kills the process, NOT `waitFor(timeout, unit)`. On Android that
            // overload is java.lang.Process's default, which polls by sleeping in steps of up to 100 ms, so a
            // compiler that finished in 25 ms was reported ~100 ms later -- measured on the emulator, it was
            // most of what a completion request cost once the preamble was precompiled. It also only started
            // counting after stdout closed, so it never caught a process that hung with stdout open.
            val timedOut = AtomicBoolean(false)
            val watchdog = WATCHDOG.schedule({
                timedOut.set(true)
                process.destroyForcibly()
            }, timeoutSeconds, TimeUnit.SECONDS)

            try {
                // Write the buffer and CLOSE, before reading a byte back. The other order deadlocks as soon as
                // the input is larger than the pipe buffer: the compiler blocks writing diagnostics that nobody
                // is draining, while we block writing source that it is not reading.
                if (stdin != null) {
                    runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } }
                } else {
                    runCatching { process.outputStream.close() }
                }

                // Killing the process (a cancel or the watchdog) closes its streams, which on Android makes
                // this read throw InterruptedIOException rather than see EOF. That is the expected end of a
                // run we stopped ourselves, not a failure to report.
                val output = try {
                    process.inputStream.bufferedReader().readText()
                } catch (e: IOException) {
                    if (timedOut.get() || cancellation?.isCancelled == true) "" else throw e
                }
                val exit = process.waitFor()
                if (timedOut.get()) return ToolResult(false, "timed out after ${timeoutSeconds}s\n$output")
                if (cancellation?.isCancelled == true) return ToolResult(false, "cancelled")
                ToolResult(exit == 0, output.trim())
            } finally {
                watchdog.cancel(false)
            }
        } catch (t: Throwable) {
            log.warn("could not run ${command.firstOrNull()}", t)
            ToolResult(false, t.message ?: t.toString())
        }
    }

    companion object {
        /** Kills a tool that outlives its timeout. One daemon thread for every toolchain; it only ever waits. */
        private val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ndk-tool-watchdog").apply { isDaemon = true }
        }

        /** `libllvmtools.so`: clang, ar, nm, strip and the rest, selected by the first argument. */
        const val DRIVER_LIB = "llvmtools"

        /** `libld-gnu-lld.so`: the same binary under a name that reaches lld and tells it to be GNU-flavored. */
        const val LINKER_LIB = "ld-gnu-lld"

        /** `libndkrun.so`: runs a user's console program in a pseudo-terminal. */
        const val RUN_LAUNCHER_LIB = "ndkrun"

        const val ASSET_ARCHIVE = "assets/toolchain.zip"
        const val LLVM_MAJOR = "18"

        /**
         * The API level the sysroot's link stubs and CRT objects were taken at; the NDK's `<triple><api>`.
         * It is also the level every ABI's triple carries, since the build script ships one level per ABI.
         */
        const val API_LEVEL = 26

        /** arm64's target, kept for callers that predate [NdkAbi]. New code asks the ABI for its [NdkAbi.target]. */
        const val TARGET_TRIPLE = "aarch64-linux-android$API_LEVEL"

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
