package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where an on-device dex invocation should run when a forked VM is available, and what happens when that VM
 * dies. Shared by the forked dexer so both rules are testable without a device.
 *
 * A forked VM costs a process launch, a class load of the tool and a heap reservation of twice its `-Xmx` (see
 * `dev.ide.platform.ForkedToolVm`). That buys headroom above the app heap for a big merge, and nothing for a
 * small one: the native-multidex merge splits even a 150-class app into one invocation per core, and forking a
 * 1.5GB VM for each of those a few dozen classes pays the whole launch cost eight times to do work the app heap
 * holds easily. Worse, every launch is another chance to be refused the reservation.
 *
 * A refused reservation does not fail politely. ART aborts in `Runtime::Init`
 * (`Check failed: main_mem_map_1.IsValid() Failed anonymous mmap(…)`), the launcher reports SIGABRT, and the
 * merge had no result at all rather than a wrong one, so it can simply be run again in-process. The same holds
 * for a launch the OS refuses and for a VM the low-memory killer takes: none of them says anything about the
 * inputs.
 */
object ForkedDexPolicy {

    /** Whether an invocation over [inputBytes] of input is big enough to be worth a forked VM. */
    fun worthForking(inputBytes: Long, thresholdBytes: Long): Boolean = inputBytes >= thresholdBytes

    /** Total size of [inputs] on disk; a missing or unreadable input counts as empty. */
    fun inputBytes(inputs: List<Path>): Long =
        inputs.sumOf { runCatching { if (Files.isRegularFile(it)) Files.size(it) else 0L }.getOrDefault(0L) }

    /**
     * Run [forked]; if its process never reached a verdict ([ToolResult.processFailed]), report it through
     * [onProcessFailure], drop whatever `.dex` the dead VM left directly in [outDir] (a partial `classes2.dex` would
     * otherwise be packaged next to the retry's output) and run [inProcess] instead.
     *
     * A fork that ran and REPORTED a failure (a real dex error, a duplicate class) is returned as it is: the same
     * inputs would fail the same way in-process, and its message is the one the user needs.
     */
    fun runWithFallback(
        outDir: Path,
        forked: () -> ToolResult,
        inProcess: () -> ToolResult,
        onProcessFailure: (ToolResult) -> Unit = {},
    ): ToolResult {
        val r = forked()
        if (r.success || !r.processFailed) return r
        onProcessFailure(r)
        clearDexOutput(outDir)
        val retry = inProcess()
        val reason = r.log.lastOrNull { it.isNotBlank() }?.trim() ?: "no output"
        return retry.copy(log = listOf("dex: the forked VM did not finish ($reason); ran in-process instead") + retry.log)
    }

    private fun clearDexOutput(outDir: Path) {
        if (!Files.isDirectory(outDir)) return
        runCatching {
            Files.list(outDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".dex") }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
