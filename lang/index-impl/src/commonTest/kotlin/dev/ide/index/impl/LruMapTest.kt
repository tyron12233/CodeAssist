package dev.ide.index.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bounded access-ordered map, which stands in for a JVM-only `LinkedHashMap` constructor.
 *
 * The two things it holds are cached blocks and open file handles, and both failure modes are quiet: an
 * eviction rule that keeps the wrong entry costs a re-read nobody notices, and a handle evicted without
 * being closed leaks a file descriptor until the process hits its limit and an unrelated `open()` fails.
 * Neither shows up in a test of the index itself, so they are stated here.
 */
class LruMapTest {

    @Test
    fun theLeastRecentlyUsedEntryIsTheOneEvicted() {
        val evicted = ArrayList<String>()
        val map = LruMap<String, Int>(maxSize = 3) { key, _ -> evicted.add(key) }
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        map["d"] = 4
        assertEquals(listOf("a"), evicted)
        assertNull(map["a"])
        assertEquals(3, map.size)
    }

    @Test
    fun readingAnEntryMakesItYoungAgain() {
        // This is the whole difference between an access-ordered map and an insertion-ordered one, and it is
        // what a cache is for: the block a query keeps coming back to must outlive one it read once.
        val evicted = ArrayList<String>()
        val map = LruMap<String, Int>(maxSize = 2) { key, _ -> evicted.add(key) }
        map["hot"] = 1
        map["cold"] = 2
        assertEquals(1, map["hot"])
        map["new"] = 3
        assertEquals(listOf("cold"), evicted, "the one not read is the one dropped")
        assertEquals(1, map["hot"])
    }

    @Test
    fun overwritingAnEntryDoesNotEvictAnything() {
        val evicted = ArrayList<String>()
        val map = LruMap<String, Int>(maxSize = 2) { key, _ -> evicted.add(key) }
        map["a"] = 1
        map["b"] = 2
        map["a"] = 11
        assertEquals(emptyList(), evicted)
        assertEquals(11, map["a"])
        assertEquals(2, map.size)
    }

    @Test
    fun anExplicitRemoveDoesNotRunTheEvictionHook() {
        // The caller that removes a handle closes it itself; running the hook too would close it twice.
        val evicted = ArrayList<String>()
        val map = LruMap<String, Int>(maxSize = 4) { key, _ -> evicted.add(key) }
        map["a"] = 1
        assertEquals(1, map.remove("a"))
        assertEquals(emptyList(), evicted)
        assertNull(map.remove("a"), "removing twice is null, not an exception")
    }

    @Test
    fun removeKeysDropsEverySegmentsEntriesAtOnce() {
        val map = LruMap<Long, String>(maxSize = 100)
        for (seg in 0L until 3L) for (block in 0L until 4L) map[(seg shl 40) or block] = "s$seg:b$block"
        map.removeKeys { (it ushr 40) == 1L }
        assertEquals(8, map.size)
        assertNull(map[(1L shl 40) or 2L])
        assertEquals("s2:b3", map[(2L shl 40) or 3L])
    }

    @Test
    fun aBoundOfOneKeepsOnlyTheNewest() {
        val evicted = ArrayList<String>()
        val map = LruMap<String, Int>(maxSize = 1) { key, _ -> evicted.add(key) }
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        assertEquals(listOf("a", "b"), evicted)
        assertEquals(1, map.size)
        assertEquals(3, map["c"])
    }
}
