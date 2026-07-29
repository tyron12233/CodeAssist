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

    fun reset() {
        parseNanos.set(0); parseCount.set(0); parseBytes.set(0)
    }

    fun snapshot(): String =
        "parse: ${parseCount.get()} classes, ${parseBytes.get() / 1024}KB, ${parseNanos.get() / 1_000_000}ms"
}
