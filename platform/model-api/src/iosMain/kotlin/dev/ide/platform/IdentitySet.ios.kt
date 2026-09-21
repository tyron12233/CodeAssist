@file:OptIn(ExperimentalNativeApi::class)

package dev.ide.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

/**
 * Buckets keyed by the runtime's own identity hash, scanned with `===`.
 *
 * `identityHashCode` is the same primitive `IdentityHashMap` is built on, and it is stable for the life of
 * an object, so this is the JVM structure rather than an approximation of it. A bucket is a list because two
 * distinct objects may share a hash; in practice they rarely do, so the scan is over one element.
 */
actual class IdentitySet<T : Any> actual constructor() {
    private val buckets = HashMap<Int, MutableList<T>>()
    private val order = ArrayList<T>()

    actual fun add(element: T): Boolean {
        val bucket = buckets.getOrPut(element.identityHashCode()) { ArrayList(1) }
        if (bucket.any { it === element }) return false
        bucket.add(element)
        order.add(element)
        return true
    }

    actual operator fun contains(element: T): Boolean =
        buckets[element.identityHashCode()]?.any { it === element } == true

    actual fun toList(): List<T> = order.toList()

    actual val size: Int get() = order.size
}
