package dev.ide.index.impl

/**
 * A bounded map that evicts entries the way an LRU does, approximately: by second chance (CLOCK).
 *
 * `LinkedHashMap(capacity, loadFactor, accessOrder = true)` with an overridden `removeEldestEntry` is the
 * exact LRU, and it is a JVM-only constructor: common Kotlin's `LinkedHashMap` keeps insertion order and has
 * no eviction hook. Recovering access order by removing and reinserting an entry on every hit worked, but
 * every hit then allocated a map entry, and the block cache is hit for every block an index query touches:
 * on a device that was a steady share of the editor's garbage while typing.
 *
 * So a hit only marks its entry used. Eviction walks from the oldest entry: a used one is unmarked and moved
 * to the young end (its second chance), an unused one is evicted. Entries hit since they last came round are
 * kept, which is what an LRU would keep in all but the order it gives up the rest.
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

    private class Slot<V>(val value: V) {
        var used = false
    }

    private val entries = LinkedHashMap<K, Slot<V>>()

    val size: Int get() = entries.size

    val values: Collection<V> get() = entries.values.map { it.value }

    /** The value for [key], marked used so the next eviction pass gives it a second chance. */
    operator fun get(key: K): V? {
        val slot = entries[key] ?: return null
        slot.used = true
        return slot.value
    }

    /** Insert [value], evicting entries until the map is within its bound. */
    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = Slot(value)
        var secondChances = 0
        while (entries.size > maxSize) {
            val eldest = entries.keys.first()
            val slot = entries.getValue(eldest)
            // Bounded so a map whose every entry is marked still evicts within one pass.
            if (slot.used && secondChances < entries.size) {
                slot.used = false
                entries.remove(eldest)
                entries[eldest] = slot
                secondChances++
                continue
            }
            entries.remove(eldest)
            onEvict(eldest, slot.value)
        }
    }

    /** Remove [key] without running [onEvict] — the caller asked for it and owns what comes back. */
    fun remove(key: K): V? = entries.remove(key)?.value

    /** Drop every entry whose key satisfies [predicate], without running [onEvict]. */
    fun removeKeys(predicate: (K) -> Boolean) {
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) iterator.remove()
        }
    }

    fun clear() = entries.clear()
}
