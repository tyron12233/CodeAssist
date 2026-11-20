package dev.ide.embedded

import dev.ide.android.support.tools.Aapt2InProcess
import dev.ide.android.support.tools.ToolResult
import java.io.File

/**
 * aapt2 as the iOS app carries it: a library in the app, entered through JNI.
 *
 * The app registers [run] when it has the library and says so with the `codeassist.aapt2.native` property;
 * [install] then makes it the engine's aapt2. What aapt2 prints goes to a file for the length of a run, because
 * in one process its output is the process's own.
 */
object NativeAapt2 {
    /** Runs aapt2's `main` with [args] (no program name), its output written to [output]. Returns its exit code. */
    @JvmStatic
    external fun run(args: Array<String>, output: String): Int

    private val lock = Any()

    fun install() {
        if (System.getProperty("codeassist.aapt2.native") != "true" || Aapt2InProcess.runner != null) return
        install(::run)
    }

    /** Makes [run] (aapt2's `main` with its output sent to a file, as [NativeAapt2.run]) the engine's aapt2. */
    fun install(run: (args: Array<String>, output: String) -> Int) {
        Aapt2InProcess.runner = { args ->
            synchronized(lock) {
                val output = File.createTempFile("aapt2", ".log")
                try {
                    val code = run(args.toTypedArray(), output.path)
                    val lines = output.readLines()
                    if (code == 0) ToolResult.ok(lines) else ToolResult(false, lines + "aapt2 exited with code $code")
                } catch (t: Throwable) {
                    ToolResult(false, listOf("aapt2 could not be called: $t"), processFailed = true)
                } finally {
                    output.delete()
                }
            }
        }
        val version = Aapt2InProcess.runner?.invoke(listOf("version"))
        System.err.println("aapt2 in this process: ${version?.log?.joinToString(" ")} (${if (version?.success == true) "ok" else "FAILED"})")
    }
}

/** SHA-256 of a file by the app's native code (see [dev.ide.build.NativeFileHash] for why). */
object NativeDigest {
    /** The file's digest, or null when it could not be read. */
    @JvmStatic
    external fun sha256(path: String): ByteArray?

    fun install() {
        if (System.getProperty("codeassist.sha256.native") != "true" || dev.ide.build.NativeFileHash.sha256 != null) return
        dev.ide.build.NativeFileHash.sha256 = { path -> runCatching { sha256(path.toString()) }.getOrNull() }
    }
}

/**
 * D8 as the iOS app carries it: compiled ahead of time into a library of the app (see
 * [dev.ide.android.support.tools.D8Native] for why), entered through JNI.
 */
object NativeD8 {
    /** Runs D8 with the arguments in [argFile], one per line; what it reports goes to [log]. Returns its exit code. */
    @JvmStatic
    external fun run(argFile: String, log: String): Int

    fun install() {
        if (System.getProperty("codeassist.d8.native") != "true" || dev.ide.android.support.tools.D8Native.runner != null) return
        dev.ide.android.support.tools.D8Native.runner = { argFile, log -> run(argFile.toString(), log.toString()) }
        System.err.println("D8 in this process: compiled ahead of time")
    }
}

/**
 * The Kotlin compiler as the iOS app carries it: compiled ahead of time into a library of the app (see
 * [dev.ide.lang.kotlin.compile.KotlincNative] for why), entered through JNI.
 */
object NativeKotlinc {
    /** Compiles the request in [requestFile], writing the result to [resultFile]. Non-zero when it wrote none. */
    @JvmStatic
    external fun run(requestFile: String, resultFile: String): Int

    fun install() {
        if (System.getProperty("codeassist.kotlinc.native") != "true" || dev.ide.lang.kotlin.compile.KotlincNative.runner != null) return
        dev.ide.lang.kotlin.compile.KotlincNative.runner = { request, result -> run(request.toString(), result.toString()) }
        System.err.println("kotlinc in this process: compiled ahead of time")
    }
}
