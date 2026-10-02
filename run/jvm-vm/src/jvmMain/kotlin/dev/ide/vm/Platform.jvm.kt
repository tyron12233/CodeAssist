package dev.ide.vm

internal actual fun identityHash(o: Any): Int = System.identityHashCode(o)
internal actual fun javaFloatToString(f: Float): String = f.toString()
internal actual fun javaDoubleToString(d: Double): String = d.toString()
internal actual fun nanoTime(): Long = System.nanoTime()
