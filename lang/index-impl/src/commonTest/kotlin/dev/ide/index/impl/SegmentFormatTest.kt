package dev.ide.index.impl

import dev.ide.kotlin.classfile.deleteFile
import dev.ide.kotlin.classfile.readFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The segment format, built and read back on whichever platform this runs on.
 *
 * The claim is not that a segment written here can be read here — a format that round-trips with itself can
 * be wrong in both directions at once. It is that **every platform writes the same bytes**, because an index
 * built on the desktop is opened on the phone and the other way round. So the bytes are checked in, and both
 * platforms are held to them.
 *
 * That also keeps the fixture honest in the other direction: it was generated before the writer was ported
 * off `java.io`, so a JVM run passing here is the statement that the port changed no byte of the output, and
 * an iOS run passing is the statement that a different file API, a different buffer and a different rename
 * produce the same file.
 */
class SegmentFormatTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun thisPlatformWritesTheSameBytesAsEveryOther() {
        val path = scratchPath("format.seg")
        deleteFile(path)
        writeSegment(path, SegmentFixture.extension(), SegmentFixture.ENTRIES)

        val written = assertNotNull(readFile(path), "the writer produced no file at $path")
        assertEquals(
            SegmentFixture.BASE64,
            Base64.encode(written),
            "the segment format changed, or this platform disagrees about it; if the change is intended," +
                " replace SegmentFixture.BASE64 with the value above",
        )
    }

    @Test
    fun aSegmentWrittenHereIsQueryableHere() {
        // Not redundant with the byte comparison: identical bytes and a broken reader is a live possibility,
        // and this is the whole pipeline — sort, spill, merge, assemble, reopen, query.
        val path = scratchPath("roundtrip.seg")
        deleteFile(path)
        writeSegment(path, SegmentFixture.extension(), SegmentFixture.ENTRIES)

        val segment = Segment.open(path, SegmentFixture.extension(), BlockCache(maxBytes = 256, blockSize = 64), 0)
        try {
            assertEquals(listOf<Any>("value42"), ArrayList<Any>().also { segment.exact("Item042", it) })
            assertEquals(listOf<Any>("shared", "second"), ArrayList<Any>().also { segment.exact("Widget", it) })
        } finally {
            segment.close()
        }
    }

    @Test
    fun spillingIsByteIdenticalToNotSpilling() {
        // The external merge sort exists so a large artifact never holds all its entries at once, and its
        // output has to be indistinguishable from the path that never spills — otherwise the same classpath
        // indexed on a small-heap phone and a desktop produces two different content-addressed segments.
        val spilled = scratchPath("spilled.seg")
        val inMemory = scratchPath("inmemory.seg")
        deleteFile(spilled)
        deleteFile(inMemory)

        SegmentWriter(spilled, SegmentFixture.extension(), maxBufferedEntries = 16, maxBufferedTrigrams = 64, regionSpillBytes = 512).use { w ->
            for (e in SegmentFixture.ENTRIES) w.add(e.term, e.value, e.origin)
            w.finish()
        }
        SegmentWriter(inMemory, SegmentFixture.extension(), maxBufferedEntries = Int.MAX_VALUE, maxBufferedTrigrams = Int.MAX_VALUE, regionSpillBytes = Int.MAX_VALUE).use { w ->
            for (e in SegmentFixture.ENTRIES) w.add(e.term, e.value, e.origin)
            w.finish()
        }

        val a = assertNotNull(readFile(spilled))
        val b = assertNotNull(readFile(inMemory))
        assertTrue(a.isNotEmpty())
        assertTrue(a.contentEquals(b), "a spilling build must be byte-identical to a non-spilling one")
    }
}
