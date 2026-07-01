package dev.ide.android

import android.app.ActivityManager
import android.content.Context
import dev.ide.platform.log.Log
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Shared on-device machinery for running R8 in a FORKED command-line VM (the release/minify OOM fix,
 * docs/build-process-isolation.md) — used by [ForkedR8Shrinker] (the build path) and the "Detect device
 * limit" settings action ([detectCeiling]).
 *
 * A forked `dalvikvm`/`art` VM is NOT a zygote app process, so its `-Xmx` can exceed the app's `largeHeap`
 * cap (576MB on the measured device, ceiling ~1.5GB). R8's classes ship as a dedicated dex asset
 * (`r8.dex.zip`, the `bundleR8DexAsset` build task) because the app's own copy is in secondary dexes a bare
 * `-cp base.apk` won't load.
 */
object R8ForkSupport {
    private val log = Log.logger("ide.mem")
    const val R8_DEX_ASSET = "r8.dex.zip"

    /** VM binaries that take `-cp <dexes> <class>` and build a multidex-aware classloader, inheriting
     *  BOOTCLASSPATH from this app process. First existing wins. `app_process` is unusable here (it resolves
     *  the start class via the system loader, which misses an app class in a large multidex apk). */
    val LAUNCHERS = listOf(
        "/apex/com.android.art/bin/dalvikvm64",
        "/apex/com.android.art/bin/dalvikvm32",
        "/apex/com.android.art/bin/dalvikvm",
        "/system/bin/dalvikvm",
    )

    fun launcher(): String? = LAUNCHERS.firstOrNull { File(it).exists() }

    /**
     * Extract `assets/r8.dex.zip` → `cacheDir/r8-dex/` and return its `classes*.dex`, made READ-ONLY.
     *
     * The read-only part is load-bearing: ART refuses to load a WRITABLE dex on a command-line VM's classpath
     * (W^X — `SecurityException: Writable dex file '…' is not allowed`, aborting the VM at system-classloader
     * creation). A freshly-extracted file is writable, so each is `setReadOnly()` after writing. Marker-guarded
     * by the app's `lastUpdateTime` so a new APK (possibly a new r8) re-extracts; the stale read-only files are
     * cleared first so the rewrite can't hit a read-only file.
     */
    fun extractR8Dexes(context: Context): List<File>? {
        val ctx = context.applicationContext
        val dir = File(ctx.cacheDir, "r8-dex")
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L).toString()
        val marker = File(dir, ".extracted")
        fun dexes() = dir.listFiles { f -> f.name.endsWith(".dex") }?.toList()?.sortedBy { it.name }
        dexes()?.takeIf { marker.exists() && marker.readText() == stamp && it.isNotEmpty() }?.let { return it }
        dir.mkdirs()
        // Clear any stale (read-only) dexes from a prior extract so the fresh write can't hit a read-only file.
        dir.listFiles()?.forEach { runCatching { it.setWritable(true); it.delete() } }
        return runCatching {
            ctx.assets.open(R8_DEX_ASSET).use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".dex")) {
                            val f = File(dir, File(e.name).name)
                            f.outputStream().use { out -> zis.copyTo(out) }
                            f.setReadOnly() // ART won't load a writable dex on a VM classpath (W^X)
                        }
                        e = zis.nextEntry
                    }
                }
            }
            marker.writeText(stamp)
            dexes()?.takeIf { it.isNotEmpty() }
        }.onFailure { log.warn("r8-fork: failed to extract $R8_DEX_ASSET: ${it.message}") }.getOrNull()
    }

    /** True if a forked `launcher -Xmx<n>m -cp <r8 dexes> R8 --version` starts (heap granted + R8 loaded). */
    fun canFork(launcher: String, dexes: List<File>, xmxMb: Int): Boolean = runCatching {
        val cp = dexes.joinToString(File.pathSeparator) { it.absolutePath }
        val proc = ProcessBuilder(launcher, "-Xmx${xmxMb}m", "-cp", cp, "com.android.tools.r8.R8", "--version")
            .redirectErrorStream(true).start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return false
        }
        proc.exitValue() == 0
    }.getOrDefault(false)

    /**
     * The largest heap (MB) a forked VM grants while loading R8, or null if forking is unavailable (no
     * launcher / missing asset). Scans the ladder ascending and stops at the first rejection — heap
     * reservation is monotonic in `-Xmx`, so the last accepted value is the ceiling. Forks several VMs
     * (~0.5s each), so call off the main thread; intended for the on-demand "Detect device limit" action.
     */
    fun detectCeiling(context: Context): Int? {
        val launcher = launcher() ?: return null
        val dexes = extractR8Dexes(context) ?: return null
        var ceiling: Int? = null
        for (mb in CEILING_LADDER) {
            if (canFork(launcher, dexes, mb)) ceiling = mb else break
        }
        log.info("r8-fork: detected forked-VM ceiling = ${ceiling ?: "none"}MB (app cap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB)")
        return ceiling
    }

    private val CEILING_LADDER = listOf(768, 1024, 1536, 2048, 3072, 4096)

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
