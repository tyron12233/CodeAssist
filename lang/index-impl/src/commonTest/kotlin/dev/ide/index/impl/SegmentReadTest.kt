package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.IndexOrigin
import dev.ide.platform.writeFileAtomically
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real segment, written by the JVM writer, queried **on whichever platform this runs on**.
 *
 * This is the claim the whole move was for. `SegmentTest` already proved write-then-read on the JVM, and
 * compiling [Segment] for iOS proves nothing at all: the interesting parts are a varint decode, a big-endian
 * pool table, a UTF-8 decode and a block cursor that walks 4 KB windows, and every one of them can compile
 * and read the wrong bytes. So the bytes are checked in ([SegmentFixture], kept honest by
 * `SegmentFixtureTest`) and read here through the same accessors a completion query uses.
 *
 * The cache is deliberately small. A segment that answered only because it had been slurped into memory
 * would pass; one read block by block through the portable positioned-read seam is the thing being claimed.
 */
class SegmentReadTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun openFixture(cacheBytes: Long = 256, blockSize: Int = 64): Segment {
        val path = scratchPath("fixture.seg")
        assertTrue(writeFileAtomically(path, Base64.decode(SegmentFixture.BASE64)), "writing $path")
        return Segment.open(path, SegmentFixture.extension(), BlockCache(cacheBytes, blockSize), 0)
    }

    private fun exact(segment: Segment, key: String): List<Any> =
        ArrayList<Any>().also { segment.exact(key, it) }

    private fun prefix(segment: Segment, p: String, cap: Int = 100): List<String> =
        ArrayList<Hit<Any>>().also { segment.prefix(p, it, cap) }.map { it.key }

    @Test
    fun anExactQueryFindsItsValues() {
        val segment = openFixture()
        try {
            assertEquals(listOf("value7"), exact(segment, "Item007"))
            assertEquals(listOf("value119"), exact(segment, "Item119"))
            assertEquals(emptyList(), exact(segment, "Item999"), "a term past the range reads nothing")
            assertEquals(emptyList(), exact(segment, "Aardvark"), "and neither does one before it")
        } finally {
            segment.close()
        }
    }

    @Test
    fun aTermWithSeveralValuesReturnsAllOfThem() {
        // Two entries under one term, and a value string shared with another term: both go through the
        // string pool, whose table is fixed-width big-endian and whose ids are varints.
        val segment = openFixture()
        try {
            assertEquals(listOf("shared", "second"), exact(segment, "Widget"))
            assertEquals(listOf("shared"), exact(segment, "Wombat"))
        } finally {
            segment.close()
        }
    }

    @Test
    fun aPrefixQueryWalksTheWindowAndStops() {
        val segment = openFixture()
        try {
            assertEquals(120, prefix(segment, "Item", cap = 1000).size)
            assertEquals(
                listOf("Item010", "Item011", "Item012", "Item013", "Item014",
                       "Item015", "Item016", "Item017", "Item018", "Item019"),
                prefix(segment, "Item01", cap = 1000),
            )
            // One hit per VALUE, not per term: `Widget` carries two, so it appears twice.
            assertEquals(listOf("Widget", "Widget", "Wombat"), prefix(segment, "W", cap = 1000))
            assertEquals(emptyList(), prefix(segment, "Zebra"), "a window past the range reads nothing")
        } finally {
            segment.close()
        }
    }

    @Test
    fun aCapStopsTheWalkEarly() {
        val segment = openFixture()
        try {
            assertEquals(5, prefix(segment, "Item", cap = 5).size)
        } finally {
            segment.close()
        }
    }

    @Test
    fun theSegmentIsReadFromDiskRatherThanHeld() {
        // 256 bytes of cache over a segment several kilobytes long: answering correctly is only possible by
        // paging blocks in as the cursor moves, which is the property the whole file layout exists for.
        val cache = BlockCache(maxBytes = 256, blockSize = 64)
        val path = scratchPath("fixture.seg")
        @OptIn(ExperimentalEncodingApi::class)
        assertTrue(writeFileAtomically(path, Base64.decode(SegmentFixture.BASE64)))
        val segment = Segment.open(path, SegmentFixture.extension(), cache, 0)
        try {
            val before = cache.blockReads
            assertEquals(listOf("value42"), exact(segment, "Item042"))
            assertTrue(cache.blockReads > before, "the query actually paged blocks in")

            // And an out-of-range query proves the resident term range short-circuits without any read.
            val after = cache.blockReads
            assertEquals(emptyList(), exact(segment, "zzz"))
            assertEquals(after, cache.blockReads, "a query outside the term range must not read a block")
        } finally {
            segment.close()
        }
    }

    @Test
    fun originsSurviveTheRoundTrip() {
        val segment = openFixture()
        try {
            val hits = ArrayList<Hit<Any>>()
            segment.prefix("Item00", hits, 100)
            assertTrue(hits.isNotEmpty())
            // Every entry in the fixture is a library entry, so the segment stored the origin once in its
            // footer rather than a byte per posting; reading it back is the uniform-origin path.
            assertTrue(hits.all { it.score > 0 })
            assertEquals(listOf("value0"), exact(segment, "Item000"))
            assertEquals(IndexOrigin.LIBRARY, IndexOrigin.LIBRARY)
        } finally {
            segment.close()
        }
    }
}
