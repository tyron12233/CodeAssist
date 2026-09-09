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
            return ToolResult.fail("could not launch ${command.firstOrNull()}: ${t.message}")
        }
        // Read to EOF first (drains the merged stream), then await exit — no pipe-buffer deadlock.
        val out = proc.inputStream.bufferedReader().readLines()
        val code = proc.waitFor()
        if (code == 0) return ToolResult.ok(out)
        val tool = command.first().substringAfterLast('/')
        // A process killed by signal N exits with 128+N. Decode it: a bare "code 139" is opaque, but
        // "SIGSEGV" tells the reader the native tool crashed (vs. a clean non-zero error exit), which on a
        // bundled aapt2/zipalign prebuilt usually means an ABI/page-size/kernel mismatch on this device.
        val detail = signalName(code)?.let { "$it (code $code) — the native binary crashed" } ?: "code $code"
        return ToolResult(false, out + "$tool exited with $detail")
    }

    /** Map a 128+N shell exit status to its POSIX signal name, or null if [code] is not a signal death. */
    private fun signalName(code: Int): String? = when (code - 128) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"
        else -> null
    }
}
