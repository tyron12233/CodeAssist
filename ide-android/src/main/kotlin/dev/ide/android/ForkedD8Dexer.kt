package dev.ide.android

import android.content.Context
import dev.ide.android.support.tools.D8Dexer
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.platform.log.Log
import java.nio.file.Path
import java.nio.file.Paths

/**
 * On-device D8 dexer for the dex MERGE step ([dex]), run in a FORKED command-line VM so the merge gets a heap
 * above the app cap — the merge (finalizing all class-dex at once) is the debug-path memory peak. The
 * per-class archives ([dexArchive]) stay in-process: they're small, cached, and many, so forking each would
 * cost more in VM-spawn overhead than it saves.
 *
 * Shares the R8 fork machinery ([R8ForkSupport]) and the same "R8 execution" / "R8 forked-VM heap" settings
 * as [ForkedR8Shrinker], so In-process mode keeps the merge in-process too. Resolved lazily; self-falls-back
 * to in-process D8 when forking isn't usable here.
 */
class ForkedD8Dexer(
    context: Context,
    private val modeProvider: () -> String? = { null },
    private val maxHeapMbProvider: () -> Int? = { null },
    private val fallback: Dexer = D8InProcessDexer(),
) : Dexer {
    private val log = Log.logger("ide.mem")
    private val appContext = context.applicationContext

    @Volatile
    private var note: String? = null
    private val delegate: Dexer by lazy { resolve() }

    override fun dex(
        inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path,
        threads: Int, desugaredLibConfig: Path?,
    ): ToolResult {
        val r = delegate.dex(inputs, androidJar, minApi, release, outDir, threads, desugaredLibConfig)
        return note?.let { r.copy(log = listOf(it) + r.log) } ?: r
    }

    // The per-class archive step always runs in-process (this dexer is wired only for the merge; kept safe).
    override fun dexArchive(
        inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean,
        outDir: Path, threads: Int, desugaredLibConfig: Path?,
    ): ToolResult = fallback.dexArchive(inputs, classpath, androidJar, minApi, release, outDir, threads, desugaredLibConfig)

    private fun resolve(): Dexer {
        if (modeProvider()?.lowercase() == MODE_INPROCESS) {
            note = null
            log.info("forked-D8 merge: in-process (R8 execution = In-process)")
            return fallback
        }
        val launcher = R8ForkSupport.launcher() ?: return inProcess("no forked-VM launcher")
        val dexes = R8ForkSupport.extractR8Dexes(appContext) ?: return inProcess("R8 runtime asset unavailable")
        val requested = (maxHeapMbProvider() ?: DEFAULT_XMX_MB).coerceAtLeast(MIN_XMX_MB)
        val candidates = (listOf(requested) + FALLBACK_LADDER.filter { it < requested }).distinct()
        for (xmx in candidates) {
            if (R8ForkSupport.canFork(launcher, dexes, xmx)) {
                note = "dex merge: forked VM, ${xmx}MB heap"
                log.info("forked-D8 merge: runs in a forked $launcher -Xmx${xmx}m (${dexes.size} dex)")
                return D8Dexer(dexes.map { it.toPath() }, Paths.get(launcher), listOf("-Xmx${xmx}m"))
            }
        }
        return inProcess("the device wouldn't start a forked VM at ${candidates.last()}MB+")
    }

    private fun inProcess(reason: String): Dexer {
        note = "dex merge: in-process ($reason)"
        log.warn("forked-D8 merge: $reason → in-process D8")
        return fallback
    }

    private companion object {
        const val MODE_INPROCESS = "inprocess"
        // The merge peaks ~365MB; reuse R8's heap ladder (more than enough, one shared setting).
        const val DEFAULT_XMX_MB = 1536
        const val MIN_XMX_MB = 768
        val FALLBACK_LADDER = listOf(2048, 1536, 1024, 768)
    }
}
