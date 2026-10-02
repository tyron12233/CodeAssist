package dev.ide.vm

import kotlin.native.identityHashCode
import kotlin.time.TimeSource

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun identityHash(o: Any): Int = o.identityHashCode()

// Kotlin/Native formats a Float and a Double the way the JVM does (shortest round-trip digits, `1.0E10`
// past 10^7), so `Float.toString` is Java's `Float.toString`.
internal actual fun javaFloatToString(f: Float): String = f.toString()
internal actual fun javaDoubleToString(d: Double): String = d.toString()

private val origin = TimeSource.Monotonic.markNow()
internal actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds
