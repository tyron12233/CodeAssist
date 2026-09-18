package dev.ide.platform

/**
 * A plain map behind the module's recursive lock.
 *
 * [getOrPut] holds the lock ACROSS [compute], which `ConcurrentHashMap.computeIfAbsent` also does, so the
 * two agree on the property callers depend on: the value is computed once. The lock is recursive, so a
 * `compute` that reaches back into the same map deadlocks on neither platform.
 */
actual class ConcurrentMap<K : Any, V : Any> actual constructor() {
    private val map = HashMap<K, V>()
    private val lock = Lock()

    actual operator fun get(key: K): V? = lock.withLock { map[key] }
    actual operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }
    actual fun remove(key: K): V? = lock.withLock { map.remove(key) }
    actual fun clear() = lock.withLock { map.clear() }
    actual fun getOrPut(key: K, compute: () -> V): V = lock.withLock { map.getOrPut(key, compute) }
    actual val size: Int get() = lock.withLock { map.size }
    actual val keys: Set<K> get() = lock.withLock { map.keys.toSet() }
    actual val values: Collection<V> get() = lock.withLock { map.values.toList() }
}
