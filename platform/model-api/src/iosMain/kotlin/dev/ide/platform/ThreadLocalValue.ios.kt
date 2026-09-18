package dev.ide.platform

import kotlin.native.concurrent.ThreadLocal

/**
 * Kotlin/Native gives an OBJECT per-thread state, not a field, so the per-thread storage is one map per
 * thread and a holder is its key. Identity-keyed, which is what a `ThreadLocal` field is.
 */
@ThreadLocal
private object Slots {
    val values = HashMap<Any, Any>()
}

actual class ThreadLocalValue<T : Any> actual constructor(private val initial: () -> T) {

    @Suppress("UNCHECKED_CAST")
    actual fun get(): T = Slots.values.getOrPut(this) { initial() } as T
}
