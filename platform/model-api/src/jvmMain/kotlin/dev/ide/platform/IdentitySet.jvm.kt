package dev.ide.platform

import java.util.Collections
import java.util.IdentityHashMap

/** `IdentityHashMap`, which is what this abstraction exists to name from common code. */
actual class IdentitySet<T : Any> actual constructor() {
    // Linked, so `toList` preserves insertion order the way the iOS actual does.
    private val backing: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
    private val order = ArrayList<T>()

    actual fun add(element: T): Boolean {
        if (!backing.add(element)) return false
        order.add(element)
        return true
    }

    actual operator fun contains(element: T): Boolean = element in backing

    actual fun toList(): List<T> = order.toList()

    actual val size: Int get() = backing.size
}
