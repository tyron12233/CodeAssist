package dev.ide.android

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dev.ide.core.IdeServicesBackend
import dev.ide.platform.DeviceMemory
import dev.ide.platform.MemoryTier
import dev.ide.platform.log.Log

/**
 * The Android half of [DeviceMemory]: classifies the device once per process, and releases the editor's
 * rebuildable caches when the system asks for memory back.
 */
object AndroidMemory {
    private val log = Log.logger("ide.mem")

    /** Physical RAM below which a device counts as [MemoryTier.LOW]. A "4 GB" phone reports about 3.6 GiB
     *  of `totalMem`; a "3 GB" one about 2.8 GiB. */
    private const val LOW_TOTAL_MEM_BYTES = 3_500L * 1024 * 1024

    /** `largeMemoryClass` (the heap a `largeHeap` app gets, in MB) at or below which the device is LOW. */
    private const val LOW_LARGE_MEMORY_CLASS_MB = 256

    /** Classify this device and publish it as [DeviceMemory.detected]. Cheap; safe in every process. */
    fun detect(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val totalMem = runCatching {
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
        val low = am.isLowRamDevice ||
            am.largeMemoryClass <= LOW_LARGE_MEMORY_CLASS_MB ||
            (totalMem in 1 until LOW_TOTAL_MEM_BYTES)
        DeviceMemory.detected = if (low) MemoryTier.LOW else MemoryTier.NORMAL
        log.info(
            "device memory: tier=${DeviceMemory.detected} lowRam=${am.isLowRamDevice} " +
                "largeMemoryClass=${am.largeMemoryClass}MB totalMem=${totalMem / (1024 * 1024)}MB",
        )
    }

    /**
     * Release the active engine's rebuildable caches when the system signals memory pressure, in the UI
     * process. Most system kills in the field land while the IDE sits in the background (typically while the
     * user runs the app they just built), and a process holding a smaller heap is both less likely to be
     * chosen and cheaper to keep. Two levels:
     *  - leaving the screen ([ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]) releases on a low-memory device
     *    only, since on a roomy one it would just make the return to the editor re-resolve for nothing;
     *  - entering the background list ([ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] and above), or critical
     *    pressure while visible, releases everywhere.
     */
    fun registerTrimHandler(context: Context, backend: IdeServicesBackend) {
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (!shouldRelease(level)) return
                log.info("onTrimMemory($level) tier=${DeviceMemory.tier}: releasing editor caches")
                backend.releaseMemory(collect = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = backend.releaseMemory(collect = true)
        })
    }

    @Suppress("DEPRECATION") // RUNNING_* levels are only delivered before API 34; harmless after.
    private fun shouldRelease(level: Int): Boolean = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> true
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> DeviceMemory.isLow
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> DeviceMemory.isLow
        else -> false
    }
}
