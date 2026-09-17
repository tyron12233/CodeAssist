package dev.ide.index.impl

/**
 * A bounded, access-ordered map that evicts its least-recently-used entry.
 *
 * `LinkedHashMap(capacity, loadFactor, accessOrder = true)` with an overridden `removeEldestEntry` is what
 * this replaces, and it is a JVM-only constructor: common Kotlin's `LinkedHashMap` keeps insertion order and
 * has no eviction hook. The behaviour is recovered from the one guarantee the common contract does make —
 * insertion order — by removing and reinserting an entry on access, which moves it to the young end. Both
 * operations are O(1), so this is the same cost as the map it replaces.
 *
 * [onEvict] runs while the caller's lock is held, because the two things evicted here need it: a file handle
 * has to be closed exactly once, and a block has to be dropped before the entry that named it is gone.
 *
 * Not thread-safe. Both users guard it with the same lock they already hold for their own state.
 */
internal class LruMap<K, V>(
    private val maxSize: Int,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {

    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = entries.size

    val values: Collection<V> get() = entries.values

    /** The value for [key], made most-recently-used. */
    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /** Insert [value], evicting the least-recently-used entries until the map is within its bound. */
    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxSize) {
            val eldest = entries.keys.firstOrNull() ?: break
            val evicted = entries.remove(eldest) ?: break
            onEvict(eldest, evicted)
        }
    }

    /** Remove [key] without running [onEvict] — the caller asked for it and owns what comes back. */
    fun remove(key: K): V? = entries.remove(key)

    /** Drop every entry whose key satisfies [predicate], without running [onEvict]. */
    fun removeKeys(predicate: (K) -> Boolean) {
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) iterator.remove()
        }
    }

    fun clear() = entries.clear()
}
