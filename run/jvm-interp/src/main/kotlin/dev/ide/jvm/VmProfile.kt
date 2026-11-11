package dev.ide.jvm

import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight always-on counters for the VM's cold-path costs — class parsing (ClassReader → VmClass) and the
 * bytes it read — so the first-render latency of a preview can be attributed without a full profiler. Two
 * atomic adds per parsed class; negligible. Read + [reset] them around a measured run.
 */
object VmProfile {
    val parseNanos = AtomicLong(0)
    val parseCount = AtomicLong(0)
    val parseBytes = AtomicLong(0)

    /** Static initializers that took long enough to matter: class name to (milliseconds, instructions),
     *  inclusive of the initializers each one set off. */
    private val slowInits = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
    val initNanos = AtomicLong(0)
    val initCount = AtomicLong(0)

    /** Record one top-level class initialization (not one triggered from inside another's). */
    fun classInitialized(name: String, nanos: Long, steps: Long, outermost: Boolean) {
        if (outermost) { initNanos.addAndGet(nanos); initCount.incrementAndGet() }
        if (nanos >= SLOW_INIT_NANOS) slowInits[name] = longArrayOf(nanos / 1_000_000, steps)
    }

    /** The slowest initializers since [reset], slowest first. */
    fun slowestInits(limit: Int = 12): String =
        slowInits.entries.sortedByDescending { it.value[0] }.take(limit)
            .joinToString(", ") { "${it.key.substringAfterLast('/')}=${it.value[0]}ms/${it.value[1]}st" }

    private const val SLOW_INIT_NANOS = 50_000_000L

    fun reset() {
        parseNanos.set(0); parseCount.set(0); parseBytes.set(0)
        initNanos.set(0); initCount.set(0); slowInits.clear()
    }

    fun snapshot(): String =
        "parse: ${parseCount.get()} classes, ${parseBytes.get() / 1024}KB, ${parseNanos.get() / 1_000_000}ms; " +
            "static-init: ${initCount.get()} classes, ${initNanos.get() / 1_000_000}ms"
}
