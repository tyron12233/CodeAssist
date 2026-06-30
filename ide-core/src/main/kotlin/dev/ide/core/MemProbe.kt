package dev.ide.core

/**
 * Phase-0 instrumentation for the build-process-isolation investigation (see
 * `docs/build-process-isolation.md`). Before splitting the build/run into its own OS process we need to
 * know WHERE the device's memory pressure actually peaks — the project-open warm-up storm (index + the two
 * retained Kotlin warm-ups) or a build/run — because the separate process only addresses the latter.
 *
 * Deliberately Java-heap only. The compile/dex/R8 workload is Java-heap-bound: on ART `largeHeap` raises
 * the Java-heap ceiling, and a compiler growing that heap is what pushes total process RSS past the system
 * limit into the low-memory killer. So `maxMemory - used` headroom is a faithful proxy for this workload.
 * If the data later shows the heap sitting far from its ceiling at an OOM, native/PSS sampling (an injected
 * Android `Debug` probe) is the next refinement.
 */

internal const val MB_BYTES = 1024L * 1024L

/** How often the build/open peak trackers sample the heap, in ms (catches a long task's intra-task peak). */
internal const val MEM_SAMPLE_INTERVAL_MS = 200L

/**
 * Emit a heap "heartbeat" log line every this-many samples during a build (≈ every
 * [MEM_SAMPLE_INTERVAL_MS] × this ms). The build's peak summary is logged only on the `finally` exit path,
 * which never runs when the OS low-memory-killer kills the process mid-task (the classic whole-program R8
 * OOM). A periodic heartbeat naming the running task leaves a logcat trail whose LAST line before the kill
 * pins which task was at the ceiling and how high it had climbed — the closest proxy to the OOM peak.
 */
internal const val MEM_HEARTBEAT_EVERY_SAMPLES = 8

/** A point-in-time Java-heap reading in MB. [headroomMb] is `max - used`: the room left before an OOM. */
internal data class MemSample(
    val usedMb: Long,
    val maxMb: Long,
    val headroomMb: Long,
    /** Sample count when this is a peak summary (1 for a single reading). */
    val samples: Int = 1,
) {
    fun fmt(): String =
        "used=${usedMb}MB / max=${maxMb}MB (headroom=${headroomMb}MB)" + if (samples > 1) " [$samples samples]" else ""

    /** String-only props for an analytics event — heap numbers only, never user content. */
    fun props(): Map<String, String> = mapOf(
        "heap_used_mb" to usedMb.toString(),
        "heap_max_mb" to maxMb.toString(),
        "heap_headroom_mb" to headroomMb.toString(),
    )

    companion object {
        fun now(): MemSample {
            val rt = Runtime.getRuntime()
            val max = rt.maxMemory()
            val used = rt.totalMemory() - rt.freeMemory()
            return MemSample(used / MB_BYTES, max / MB_BYTES, (max - used) / MB_BYTES)
        }
    }
}

/**
 * Tracks the peak Java-heap usage (and the tightest headroom) seen across a window. Sampled periodically
 * during a build/run so the true peak of a long whole-program pass (R8) is caught, not just the value at
 * task boundaries. Thread-safe: readings arrive from the sampler coroutine and the executor's task-status
 * callback concurrently.
 */
internal class PeakHeap {
    private val lock = Any()
    private var maxUsed = 0L
    private var minHeadroom = Long.MAX_VALUE
    private var maxSeen = 0L
    private var count = 0

    fun record() {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val used = rt.totalMemory() - rt.freeMemory()
        val headroom = max - used
        synchronized(lock) {
            if (used > maxUsed) maxUsed = used
            if (headroom < minHeadroom) minHeadroom = headroom
            if (max > maxSeen) maxSeen = max
            count++
        }
    }

    fun peak(): MemSample = synchronized(lock) {
        MemSample(
            usedMb = maxUsed / MB_BYTES,
            maxMb = maxSeen / MB_BYTES,
            headroomMb = (if (minHeadroom == Long.MAX_VALUE) 0L else minHeadroom).coerceAtLeast(0) / MB_BYTES,
            samples = count.coerceAtLeast(1),
        )
    }
}
