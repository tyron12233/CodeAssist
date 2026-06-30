package dev.ide.android

import android.content.Context
import dev.ide.android.support.tools.L8Request
import dev.ide.android.support.tools.R8InProcessShrinker
import dev.ide.android.support.tools.R8Subprocess
import dev.ide.android.support.tools.ShrinkRequest
import dev.ide.android.support.tools.Shrinker
import dev.ide.android.support.tools.ToolResult
import dev.ide.platform.log.Log
import java.nio.file.Paths

/**
 * On-device R8 shrinker that runs R8 in a FORKED command-line VM (`dalvikvm64 -Xmx<n>m`) instead of
 * in-process — the fix for the release/minify OOM (docs/build-process-isolation.md).
 *
 * R8's whole-program pass peaks ~548MB, but an app process's managed heap is capped at the device's
 * `dalvik.vm.heapsize` (576MB on the measured device) no matter the physical RAM — so the in-process
 * [R8InProcessShrinker] OOMs on tighter devices. A VM launched from the command line is NOT a zygote app
 * process, so its `-Xmx` can exceed that cap (measured ~1.5GB on a 12GB device). The fork machinery (launcher
 * discovery, R8 dex asset, capability check) lives in [R8ForkSupport].
 *
 * Resolved lazily on first use; self-falls-back to in-process R8 when forking isn't usable (no launcher,
 * missing asset, or no grantable `-Xmx` at which R8 loads) — so a device where this can't work behaves
 * exactly as before. The actual invocation reuses [R8Subprocess] (same CLI arg-building as the desktop path),
 * with the `dalvikvm64` launcher, R8's dexes as the `-cp`, and a big `-Xmx`.
 */
class ForkedR8Shrinker(
    context: Context,
    /** The "R8 execution" setting: `auto` (fork when possible, else in-process), `forked` (require the forked
     *  VM — fail the build if it can't start), `inprocess` (never fork). Null/unknown → auto. */
    private val modeProvider: () -> String? = { null },
    /** The user's "R8 forked-VM heap" setting in MB, or null for the default. The forked VM tries this first,
     *  then steps down (auto) if the device can't grant it. */
    private val maxHeapMbProvider: () -> Int? = { null },
    private val fallback: Shrinker = R8InProcessShrinker(),
) : Shrinker {
    private val log = Log.logger("ide.mem")
    private val appContext = context.applicationContext

    /** A one-line note prepended to the build-console log (via [ToolResult]) so the chosen heap is VISIBLE in
     *  the UI, not just logcat. Set when [delegate] resolves. */
    @Volatile
    private var note: String? = null

    // Resolve once: the forked R8Subprocess if a launcher + asset + grantable heap line up, else the fallback.
    private val delegate: Shrinker by lazy { resolve() }

    override fun shrink(request: ShrinkRequest): ToolResult = withNote(delegate.shrink(request))
    override fun l8(request: L8Request): ToolResult = withNote(delegate.l8(request))

    private fun withNote(result: ToolResult): ToolResult =
        note?.let { result.copy(log = listOf(it) + result.log) } ?: result

    private fun resolve(): Shrinker {
        // Default is forked (only an explicit In-process opts out).
        if (modeProvider()?.lowercase() == MODE_INPROCESS) {
            note = "R8: in-process (set to In-process in Build Runtime settings)"
            log.info("forked-R8: mode=in-process → in-process R8")
            return fallback
        }
        val launcher = R8ForkSupport.launcher()
            ?: return forkUnavailable("no forked-VM launcher on this device")
        val dexes = R8ForkSupport.extractR8Dexes(appContext)
            ?: return forkUnavailable("R8 runtime asset unavailable")
        // The requested max heap, then a ladder of smaller heaps to step down to if the device can't grant it.
        // Per-candidate capability check (heap accepted AND R8 loads).
        val requested = (maxHeapMbProvider() ?: DEFAULT_XMX_MB).coerceAtLeast(MIN_XMX_MB)
        val candidates = (listOf(requested) + FALLBACK_LADDER.filter { it < requested }).distinct()
        for (xmx in candidates) {
            if (R8ForkSupport.canFork(launcher, dexes, xmx)) {
                note = if (xmx < requested) {
                    log.warn("forked-R8: requested ${requested}MB exceeds this device's limit; R8 runs at ${xmx}MB instead")
                    "R8: forked VM, ${xmx}MB heap (requested ${requested}MB exceeds the device limit)"
                } else {
                    log.info("forked-R8: R8 runs in a forked $launcher -Xmx${xmx}m (${dexes.size} dex) — above the app heap cap")
                    "R8: forked VM, ${xmx}MB heap (vs the ${Runtime.getRuntime().maxMemory() / MB}MB app limit)"
                }
                return R8Subprocess(dexes.map { it.toPath() }, Paths.get(launcher), listOf("-Xmx${xmx}m"))
            }
        }
        return forkUnavailable("the device wouldn't start a forked VM at ${candidates.last()}MB+")
    }

    /** Forking isn't usable here — fall back to in-process R8 with a build-console note explaining why (the
     *  default Forked VM mode degrades gracefully rather than failing the build). */
    private fun forkUnavailable(reason: String): Shrinker {
        note = "R8: in-process ($reason)"
        log.warn("forked-R8: $reason → in-process R8")
        return fallback
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val MODE_INPROCESS = "inprocess"
        // Heap (MB) the forked R8 falls back through if the user's requested value isn't grantable. R8 peaks
        // ~548MB, so the 768 floor still clears it; below that we'd rather just use in-process R8.
        const val DEFAULT_XMX_MB = 1536
        const val MIN_XMX_MB = 768
        val FALLBACK_LADDER = listOf(2048, 1536, 1024, 768)
    }
}
