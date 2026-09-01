package dev.ide.android

import android.app.ActivityManager
import android.content.Context
import dev.ide.platform.ForkedToolVm
import dev.ide.platform.log.Log
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Shared on-device machinery for running R8 in a FORKED command-line VM (the release/minify OOM fix,
 * docs/build-process-isolation.md) — used by [ForkedR8Shrinker] (the build path) and the "Detect device
 * limit" settings action ([detectCeiling]).
 *
 * A forked `dalvikvm`/`art` VM is NOT a zygote app process, so its `-Xmx` can exceed the app's `largeHeap`
 * cap (576MB on the measured device, ceiling ~1.5GB). R8 rides on a dedicated asset (`r8.dex.zip`, the
 * `bundleR8DexAsset` build task) holding the tool's dex AND the jar's resources.
 *
 * A fork can load the app's own APK instead, which is what the persistent Kotlin compiler VM
 * (`dev.ide.android.fork`) does. R8 and D8 keep the asset because they fork PER INVOCATION: a 180MB APK
 * classpath measures ~800ms of class loading per fork against ~130ms for the asset, and the dex merge forks
 * several times per build. The resources matter for the same reason the APK works and a bare dex does not:
 * without `resources/new_api_database.ser` R8 warns that it cannot find its API database and emits different
 * code than the same version run from the jar.
 */
object R8ForkSupport {
    private val log = Log.logger("ide.mem")
    const val R8_DEX_ASSET = "r8.dex.zip"

    /** VM binaries that take `-cp <dexes> <class>` and build a multidex-aware classloader, inheriting
     *  BOOTCLASSPATH from this app process. First existing wins. `app_process` is unusable here (it resolves
     *  the start class via the system loader, which misses an app class in a large multidex apk).
     *  Their basenames are what a tombstone reports as the faulting thread of a fork, so they are mirrored in
     *  [ForkedToolVm.LAUNCHER_THREAD_NAMES] — keep the two lists in step. */
    val LAUNCHERS = listOf(
        "/apex/com.android.art/bin/dalvikvm64",
        "/apex/com.android.art/bin/dalvikvm32",
        "/apex/com.android.art/bin/dalvikvm",
        "/system/bin/dalvikvm",
    )

    fun launcher(): String? = LAUNCHERS.firstOrNull { File(it).exists() }

    /**
     * Copy `assets/$R8_DEX_ASSET` out to `cacheDir/r8-dex/` and return it, ready to put on a fork's `-cp`.
     *
     * The asset goes on the classpath AS A ZIP rather than being unpacked into loose `classes*.dex`, because
     * a classloader built over loose dex files sees code but no resources: R8 would lose its API database,
     * its `META-INF/services` provider and its version stamp. A zip is read like an APK, so both are visible.
     *
     * It is also made READ-ONLY. ART refuses a WRITABLE dex on a command-line VM's classpath (W^X:
     * `SecurityException: Writable dex file '…' is not allowed`, aborting the VM while it builds the system
     * classloader). A zip container is not subject to that check, but the extracted copy is immutable by
     * contract and the flag costs nothing. Marker-guarded by the app's `lastUpdateTime` so a new APK (possibly
     * a new r8) re-extracts; the stale read-only file is cleared first so the rewrite can't hit it.
     */
    fun extractR8Zip(context: Context): File? {
        val ctx = context.applicationContext
        val dir = File(ctx.cacheDir, "r8-dex")
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L).toString()
        val marker = File(dir, ".extracted")
        val zip = File(dir, R8_DEX_ASSET)
        if (marker.exists() && runCatching { marker.readText() == stamp }.getOrDefault(false) &&
            zip.isFile && zip.length() > 0L
        ) {
            return zip
        }
        dir.mkdirs()
        // Clear anything from a prior extract (read-only, and possibly the old loose-dex layout) so the fresh
        // write can't hit a read-only file.
        dir.listFiles()?.forEach { runCatching { it.setWritable(true); it.delete() } }
        return runCatching {
            ctx.assets.open(R8_DEX_ASSET).use { ins -> zip.outputStream().use { ins.copyTo(it) } }
            zip.setReadOnly()
            marker.writeText(stamp)
            zip.takeIf { it.isFile && it.length() > 0L }
        }.onFailure { log.warn("r8-fork: failed to extract $R8_DEX_ASSET: ${it.message}") }.getOrNull()
    }

    /** True if a forked `launcher -Xmx<n>m -cp <r8 asset> R8 --version` starts (heap granted + R8 loaded). */
    fun canFork(launcher: String, toolClasspath: File, xmxMb: Int): Boolean = runCatching {
        val proc = ProcessBuilder(
            launcher, "-Xmx${xmxMb}m", "-cp", toolClasspath.absolutePath, "com.android.tools.r8.R8", "--version",
        ).redirectErrorStream(true).start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return false
        }
        proc.exitValue() == 0
    }.getOrDefault(false)

    /**
     * The largest heap (MB) a forked VM grants while loading R8, or null if forking is unavailable (no
     * launcher / missing asset / no heap this device can back). Scans the ladder ascending and stops at the
     * first rejection — heap reservation is monotonic in `-Xmx`, so the last accepted value is the ceiling.
     * Forks several VMs (~0.5s each), so call off the main thread.
     *
     * The ladder is first trimmed to what the device's RAM can back ([affordableHeaps]), because a rejection
     * is not a polite `false`: a VM that cannot reserve its region space ABORTS, and the OS files that abort
     * under this package. Trimming stops a small phone at a rung it can actually reach instead of walking up
     * into a certain abort, and a device that grants the whole trimmed ladder never aborts at all.
     */
    fun detectCeiling(context: Context): Int? {
        val launcher = launcher() ?: return null
        val toolClasspath = extractR8Zip(context) ?: return null
        val ladder = affordableHeaps(context, CEILING_LADDER)
        if (ladder.isEmpty()) {
            log.info("r8-fork: ${totalMemMb(context)}MB of device RAM can't back even a ${CEILING_LADDER.first()}MB fork — forking unavailable")
            return null
        }
        var ceiling: Int? = null
        for (mb in ladder) {
            if (canFork(launcher, toolClasspath, mb)) ceiling = mb else break
        }
        log.info("r8-fork: detected forked-VM ceiling = ${ceiling ?: "none"}MB (app cap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB)")
        return ceiling
    }

    private val CEILING_LADDER = listOf(768, 1024, 1536, 2048, 3072, 4096)

    /**
     * [candidates] (`-Xmx` values in MB) minus the ones this device's RAM visibly cannot back, order kept.
     * Every fork path filters through this so no path launches a VM whose only possible outcome is an ART
     * startup abort; see [ForkedToolVm.affordableHeaps] for why the bound is on 2× the heap.
     */
    fun affordableHeaps(context: Context, candidates: List<Int>): List<Int> =
        ForkedToolVm.affordableHeaps(candidates, totalMemMb(context))

    /** Device-wide physical RAM (MB) via [ActivityManager.MemoryInfo.totalMem]; 0 if unavailable. Unlike
     *  [availableMemMb] this doesn't move with load — it bounds what a heap RESERVATION can ever be. */
    fun totalMemMb(context: Context): Long = runCatching {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    }.getOrDefault(0L)

    // --- Concurrent-fork budget + process-wide gate ---------------------------------------------------------

    /** Hard cap on concurrent forked VMs regardless of how much RAM the device has — each fork still spawns a
     *  process and competes for cores, and the win over the old serial flood is mostly batching. */
    const val MAX_CONCURRENT_FORKS = 3

    /** Fraction of *available* device RAM budgeted per concurrent fork. Generous (a fork's `-Xmx` is an upper
     *  bound on its RSS, rarely the steady state) but leaves headroom so the low-memory killer stays away. */
    private const val FORK_RAM_FRACTION = 0.5

    /** Device-wide available RAM (MB) via [ActivityManager.MemoryInfo.availMem]; 0 if unavailable. */
    fun availableMemMb(context: Context): Long = runCatching {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }.getOrDefault(0L)

    /**
     * How many forked VMs of [xmxMb] this device can safely run at once. [override] (the user's "Max concurrent
     * dex forks" setting) wins when > 0; otherwise it's derived from available RAM ([FORK_RAM_FRACTION] of
     * availMem per fork), clamped to `[1, min(cores, MAX_CONCURRENT_FORKS)]`. Under memory pressure availMem
     * collapses this to 1 — one big fork, still far better than the old fork-per-library flood.
     */
    fun forkBudget(context: Context, xmxMb: Int, override: Int?): Int {
        if (override != null && override > 0) return override.coerceIn(1, 8)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val availMb = availableMemMb(context)
        val byRam = if (availMb > 0 && xmxMb > 0) ((availMb * FORK_RAM_FRACTION) / xmxMb).toInt() else 1
        return byRam.coerceIn(1, minOf(cores, MAX_CONCURRENT_FORKS))
    }

    @Volatile
    private var forkGate: Semaphore? = null

    /** Process-wide gate sized once on first use (fair, FIFO). Init-once because resizing a live semaphore is
     *  racy and the budget (device RAM / fork heap) doesn't change within a build — a setting change applies on
     *  the next build-process start. */
    @Synchronized
    private fun gate(permits: Int): Semaphore =
        forkGate ?: Semaphore(permits.coerceAtLeast(1), true).also {
            forkGate = it
            log.info("fork gate: capped at $permits concurrent forked VM(s)")
        }

    /**
     * Run [body] (a forked-VM launch) holding one permit on the process-wide concurrent-fork gate, so the
     * sibling dex-merge tasks — `mergeProjectDex`/`mergeLibDex`/`mergeExtDex` run in parallel and each forks —
     * can't collectively spawn more big-heap VMs than the device affords. Blocking acquire (callers are on an
     * IO dispatcher). [permits] sizes the gate on first use only.
     */
    fun <T> withForkPermit(permits: Int, body: () -> T): T {
        val g = gate(permits)
        g.acquire()
        return try { body() } finally { g.release() }
    }
}
