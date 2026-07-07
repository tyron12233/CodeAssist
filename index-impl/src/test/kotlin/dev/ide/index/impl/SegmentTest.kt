package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests of the on-disk [Segment] (write → open → query) over controlled corpora — they pin the
 * binary format and prove the low-RAM property: a segment opened with a deliberately tiny [BlockCache]
 * (a couple of 64-byte blocks) answers prefix/fuzzy/exact correctly over hundreds of terms, which is only
 * possible if it reads from disk on demand rather than holding the index in memory.
 */
class SegmentTest {

    private class StringIndex(private val mode: MatchingMode) : IndexExtension<String, String> {
        override val id = IndexId("test.seg")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<String> = StringExternalizer
        override val matching = mode
        override val inputFilter = InputFilter { true }
        override fun index(input: IndexInput): Map<String, Collection<String>> = emptyMap()
    }

    private fun seg(
        dir: Path,
        entries: List<IndexEntry>,
        mode: MatchingMode = MatchingMode.PREFIX_AND_FUZZY,
        cache: BlockCache = BlockCache(8L * 1024 * 1024),
    ): Segment {
        val ext = StringIndex(mode)
        val file = dir.resolve("seg.seg")
        Segment.write(file, ext, entries)
        return Segment.open(file, ext, cache, 0)
    }

    private fun entry(term: String, value: String, origin: IndexOrigin = IndexOrigin.SDK) = IndexEntry(term, value, origin)

    private fun prefix(s: Segment, p: String, cap: Int = 1000): List<Hit<Any>> =
        ArrayList<Hit<Any>>().also { s.prefix(p, it, cap) }

    private fun fuzzy(s: Segment, p: String, cap: Int = 1000): List<Hit<Any>> =
        ArrayList<Hit<Any>>().also { s.fuzzy(p, it, cap) }

    private fun exact(s: Segment, k: String): List<Any> = ArrayList<Any>().also { s.exact(k, it) }

    @Test
    fun exactPrefixFuzzyOverSmallCorpus() {
        val dir = Files.createTempDirectory("seg")
        try {
            val entries = listOf(
                entry("ArrayList", "java.util.ArrayList"),
                entry("ArrayDeque", "java.util.ArrayDeque"),
                entry("Arrays", "java.util.Arrays"),
                entry("List", "java.util.List"),
                entry("LinkedList", "java.util.LinkedList"),
                // one term, two values (multi-value postings) + distinct origin
                entry("Foo", "pkg.a.Foo", IndexOrigin.LIBRARY),
                entry("Foo", "pkg.b.Foo", IndexOrigin.SOURCE),
            )
            val s = seg(dir, entries)

            // exact: both values under "Foo"
            assertEquals(setOf("pkg.a.Foo", "pkg.b.Foo"), exact(s, "Foo").toSet())
            assertTrue(exact(s, "Nope").isEmpty())

            // prefix: only Array* and sorted by the dictionary
            val arr = prefix(s, "Array").map { it.key }
            assertEquals(listOf("ArrayDeque", "ArrayList", "Arrays"), arr)
            assertTrue(prefix(s, "Z").isEmpty())

            // fuzzy: substring "rray" surfaces ArrayList/ArrayDeque/Arrays
            assertTrue(fuzzy(s, "rray").any { (it.value as String).endsWith("ArrayList") })

            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun correctUnderATinyBlockCache() {
        val dir = Files.createTempDirectory("seg")
        try {
            // 300 fixed-width terms (so lexicographic == numeric order) → many KB on disk.
            val entries = (0 until 300).map { entry("Item%03d".format(it), "v$it") }
            // 64-byte blocks, 128-byte cap ⇒ at most 2 blocks resident: the engine MUST page from disk.
            val tiny = BlockCache(maxBytes = 128, blockSize = 64)
            val s = seg(dir, entries, cache = tiny)

            // prefix "Item1" spans Item100..Item199 — a long scan that crosses far more than 2 blocks.
            val p = prefix(s, "Item1")
            assertEquals(100, p.size)
            assertTrue(p.all { it.key.startsWith("Item1") })

            assertEquals(listOf("v150"), exact(s, "Item150"))
            assertTrue(fuzzy(s, "tem25").any { it.key == "Item250" }) // trigram lookup served from disk

            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fuzzyMatchesWhenSomeTrigramsAreAbsent() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(dir, listOf(entry("String", "String"), entry("StringBuilder", "StringBuilder"), entry("Integer", "Integer")))
            // "Strng" (typo, missing 'i') → grams str / trn / rng; only "str" exists in the corpus. The other
            // two must be skipped, leaving "String"/"StringBuilder" as candidates that the subsequence scorer keeps.
            val hits = fuzzy(s, "Strng").map { it.key }
            assertTrue("String" in hits, "expected String via the surviving trigram + scorer, got $hits")
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun prefixOnlySegmentFallsBackForFuzzy() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(
                dir,
                listOf(entry("Apple", "Apple"), entry("Apricot", "Apricot"), entry("Banana", "Banana")),
                mode = MatchingMode.PREFIX_ONLY,
            )
            // No trigram index was built; fuzzy() must degrade to prefix semantics, not crash.
            assertEquals(listOf("Apple"), fuzzy(s, "App").map { it.key })
            assertEquals(listOf("Apple", "Apricot"), prefix(s, "Ap").map { it.key })
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The streaming [SegmentWriter] must produce byte-for-byte the same segment whether or not it spills to
     * disk — otherwise the external-merge path isn't transparent and the content-addressed store's
     * deterministic-bytes invariant (two concurrent identical writers → identical files) breaks. Forces the
     * spill + k-way merge + trigram external sort via tiny caps, and compares against an all-in-memory build.
     */
    @Test
    fun spillingWriteEqualsInMemoryWriteByteForByte() {
        val dir = Files.createTempDirectory("segw")
        try {
            val ext = StringIndex(MatchingMode.PREFIX_AND_FUZZY)
            // 300 distinct terms (cross the 64-term sparse interval several times); some carry a 2nd value with
            // a distinct origin (multi-value postings + insertion-order-within-a-term sensitivity).
            val entries = ArrayList<IndexEntry>()
            for (i in 0 until 300) {
                entries.add(entry("Item%03d".format(i), "v$i", IndexOrigin.LIBRARY))
                if (i % 7 == 0) entries.add(entry("Item%03d".format(i), "alt$i", IndexOrigin.SDK))
            }

            fun build(name: String, maxE: Int, maxT: Int, spill: Int): Path {
                val f = dir.resolve(name)
                SegmentWriter(f, ext, maxBufferedEntries = maxE, maxBufferedTrigrams = maxT, regionSpillBytes = spill).use { w ->
                    for (e in entries) w.add(e.term, e.value, e.origin)
                    w.finish()
                }
                return f
            }

            // Tiny caps ⇒ many spilled runs + region temp files + a trigram external sort. (Moderate, not 1, so
            // the k-way merge stays well under the OS open-file limit.)
            val spilled = build("spilled.seg", maxE = 32, maxT = 128, spill = 512)
            // Huge caps ⇒ everything stays in memory, never spills.
            val inMem = build("inmem.seg", maxE = Int.MAX_VALUE, maxT = Int.MAX_VALUE, spill = Int.MAX_VALUE)

            assertTrue(Files.size(spilled) > 0)
            assertEquals(
                Files.readAllBytes(inMem).toList(), Files.readAllBytes(spilled).toList(),
                "a spilling build must be byte-identical to a non-spilling build",
            )

            // ...and the spilled result is a correct, queryable segment (paged from a tiny block cache). prefix
            // returns one hit per posting (value), so the count is every value whose term starts with the prefix.
            val s = Segment.open(spilled, ext, BlockCache(maxBytes = 256, blockSize = 64), 0)
            try {
                assertEquals(entries.count { it.term.startsWith("Item1") }, prefix(s, "Item1").size)
                assertEquals(setOf("v7", "alt7"), exact(s, "Item007").toSet())
                assertTrue(fuzzy(s, "tem25").any { it.key == "Item250" })
            } finally { s.close() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A value that fails to deserialize (a stale/corrupt payload — e.g. a shared externalizer whose format
     * drifted without a version bump, the on-device `UTFDataFormatException` crash) must be SKIPPED, not fatal:
     * each payload is independently length-framed so the cursor stays aligned, and the readable values under the
     * same and neighbouring terms still come back. Simulated by a reader that consumes a phantom field for any
     * value tagged `BAD`, reading past the framed payload → EOFException, exactly like reading extra fields the
     * writer never wrote.
     */
    @Test
    fun unreadableValueIsSkippedNotFatal() {
        val dir = Files.createTempDirectory("seg")
        try {
            val driftExt = object : IndexExtension<String, String> {
                override val id = IndexId("test.drift")
                override val version = 1
                override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
                override val valueExternalizer = object : Externalizer<String> {
                    override fun write(out: java.io.DataOutput, value: String) = out.writeUTF(value)
                    override fun read(inp: java.io.DataInput): String {
                        val s = inp.readUTF()
                        if (s.startsWith("BAD")) inp.readInt() // phantom field → reads past the framed payload
                        return s
                    }
                }
                override val matching = MatchingMode.PREFIX_AND_FUZZY
                override val inputFilter = InputFilter { true }
                override fun index(input: IndexInput): Map<String, Collection<String>> = emptyMap()
            }
            val file = dir.resolve("drift.seg")
            Segment.write(
                file, driftExt,
                listOf(
                    entry("Foo", "Foo.good"),
                    entry("Foo", "BAD.foo"),  // second value under Foo can't be read
                    entry("Bar", "BAD.bar"),  // Bar's only value can't be read
                    entry("Baz", "Baz.good"),
                ),
            )
            val s = Segment.open(file, driftExt, BlockCache(8L * 1024 * 1024), 0)
            try {
                assertEquals(listOf("Foo.good"), exact(s, "Foo")) // the good value survives, the bad one is skipped
                assertTrue(exact(s, "Bar").isEmpty())             // an all-bad term degrades to empty, no crash
                assertEquals(listOf("Baz.good"), exact(s, "Baz")) // a later term is unaffected (cursor stayed aligned)
                assertEquals(setOf("Baz.good"), prefix(s, "Ba").map { it.value as String }.toSet())
            } finally { s.close() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun emptySegmentIsQueryable() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(dir, emptyList())
            assertTrue(exact(s, "x").isEmpty())
            assertTrue(prefix(s, "x").isEmpty())
            assertTrue(fuzzy(s, "xyz").isEmpty())
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun camelHumpPatternsSurfaceThroughFuzzy() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(
                dir,
                listOf(
                    entry("NullPointerException", "java.lang.NullPointerException"),
                    entry("NoSuchElementException", "java.util.NoSuchElementException"),
                    entry("myDynamicList", "pkg.myDynamicList"),
                    entry("Number", "java.lang.Number"),
                ),
            )
            // A hump pattern shares no contiguous trigram with its match — served by the window scan.
            assertTrue(fuzzy(s, "NPE").any { it.key == "NullPointerException" })
            assertTrue(fuzzy(s, "mDL").any { it.key == "myDynamicList" })
            // The hump hit ranks above the looser subsequence tier.
            val npe = fuzzy(s, "NPE")
            assertTrue(
                npe.first { it.key == "NullPointerException" }.score >
                    (npe.firstOrNull { it.key == "NoSuchElementException" }?.score ?: Int.MIN_VALUE),
            )
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun humpQueriesWorkWithoutATrigramIndex() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(
                dir,
                listOf(entry("NullPointerException", "NPEv"), entry("Apple", "Apple")),
                mode = MatchingMode.PREFIX_ONLY,
            )
            // The window scan needs no trigram dictionary, so hump queries survive PREFIX_ONLY segments.
            assertTrue(fuzzy(s, "NPE").any { it.key == "NullPointerException" })
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun shortPatternsMatchCaseInsensitivelyThroughFuzzy() {
        val dir = Files.createTempDirectory("seg")
        try {
            val s = seg(dir, listOf(entry("String", "String"), entry("stack", "stack"), entry("List", "List")))
            // Below trigram length the fuzzy path used to degrade to the case-sensitive prefix scan;
            // the first-character window scan keeps it case-insensitive.
            val hits = fuzzy(s, "st").map { it.key }
            assertTrue("String" in hits && "stack" in hits, "expected both cases, got $hits")
            s.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
