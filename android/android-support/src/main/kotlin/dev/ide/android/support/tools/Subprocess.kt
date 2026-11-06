package dev.ide.android.support.tools

import java.nio.file.Path

/** Runs an external tool, merging stdout/stderr and capturing it as [ToolResult.log] for the build console. */
internal object Subprocess {
    fun run(command: List<String>, workingDir: Path? = null): ToolResult {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        workingDir?.let { pb.directory(it.toFile()) }
        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            return ToolResult(false, listOf("could not launch ${command.firstOrNull()}: ${t.message}"), processFailed = true)
        }
        // Read to EOF first (drains the merged stream), then await exit — no pipe-buffer deadlock.
        val out = proc.inputStream.bufferedReader().readLines()
        val code = proc.waitFor()
        if (code == 0) return ToolResult.ok(out)
        val tool = command.first().substringAfterLast('/')
        // A process killed by signal N exits with 128+N. Decode it: a bare "code 139" is opaque, but
        // "SIGSEGV" tells the reader the native tool crashed (vs. a clean non-zero error exit), which on a
        // bundled aapt2/zipalign prebuilt usually means an ABI/page-size/kernel mismatch on this device.
        val signal = signalName(code)
        val detail = signal?.let { "$it (code $code), the native binary crashed" } ?: "code $code"
        return ToolResult(false, out + "$tool exited with $detail", processFailed = signal != null || mainClassNotLoaded(out))
    }

    /**
     * Whether a VM launch failed before the tool ran at all, because its main class could not be loaded:
     * ART's `Unable to locate class '…'` and the JVM's `Could not find or load main class …`. That is a broken
     * launch (a tool classpath that went missing under the process), not a verdict about the inputs, so like a
     * signal death it lets the caller run the same work another way. Reported by a user as
     * `ClassNotFoundException: com.android.tools.r8.D8` failing dexExtLibs outright, where an in-process dex
     * would have succeeded.
     */
    internal fun mainClassNotLoaded(out: List<String>): Boolean =
        out.any { it.startsWith("Unable to locate class '") || it.startsWith("Error: Could not find or load main class") }

    /** Map a 128+N shell exit status to its POSIX signal name, or null if [code] is not a signal death. */
    private fun signalName(code: Int): String? = when (code - 128) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"
        else -> null
    }
}
