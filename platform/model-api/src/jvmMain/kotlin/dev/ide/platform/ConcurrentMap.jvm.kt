package dev.ide.platform

import java.util.concurrent.ConcurrentHashMap

/** The JVM actual is the real thing, so nothing about the editor's existing behaviour or cost changes. */
actual class ConcurrentMap<K : Any, V : Any> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()

    actual operator fun get(key: K): V? = map[key]
    actual operator fun set(key: K, value: V) { map[key] = value }
    actual fun remove(key: K): V? = map.remove(key)
    actual fun clear() = map.clear()
    actual fun getOrPut(key: K, compute: () -> V): V = map.computeIfAbsent(key) { compute() }
    actual val size: Int get() = map.size
    actual val keys: Set<K> get() = map.keys.toSet()
    actual val values: Collection<V> get() = map.values.toList()
}
