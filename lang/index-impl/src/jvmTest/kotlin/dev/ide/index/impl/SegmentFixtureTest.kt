package dev.ide.index.impl

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the segment the cross-platform read test queries in step with the writer that produced it.
 *
 * `SegmentTest` proves write-then-read on the JVM, and [SegmentReadTest] proves that the SAME bytes are read
 * identically on iOS — but only the JVM can write a segment, so the iOS side has to query a fixture. A
 * checked-in fixture rots: the format changes, the writer follows, and the read test keeps passing against
 * bytes nothing produces any more. This rebuilds it and compares, so a format change fails here with the
 * replacement in the message.
 */
class SegmentFixtureTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun theCheckedInFixtureIsWhatTheWriterProducesToday() {
        withTempDir("fixture") { dir ->
            val file = dir.resolve("fixture.seg")
            Segment.write(file, SegmentFixture.extension(), SegmentFixture.ENTRIES)
            val actual = Base64.encode(Files.readAllBytes(file))
            assertEquals(
                SegmentFixture.BASE64,
                actual,
                "the segment format changed; replace SegmentFixture.BASE64 with:\n$actual\n",
            )
        }
    }
}
