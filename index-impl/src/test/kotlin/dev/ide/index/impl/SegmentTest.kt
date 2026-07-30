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
import dev.ide.index.MemberExternalizer
import dev.ide.index.MemberValue
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.testkit.withTempDir
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

    /** A member index (value = [MemberValue]) — several string fields per value, so it exercises the pool. */
    private class MemberIndex : IndexExtension<String, MemberValue> {
        override val id = IndexId("test.members")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer = MemberExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { true }
        override fun index(input: IndexInput): Map<String, Collection<MemberValue>> = emptyMap()
    }

    private fun prefix(s: Segment, p: String, cap: Int = 1000): List<Hit<Any>> =
        ArrayList<Hit<Any>>().also { s.prefix(p, it, cap) }

    private fun fuzzy(s: Segment, p: String, cap: Int = 1000): List<Hit<Any>> =
        ArrayList<Hit<Any>>().also { s.fuzzy(p, it, cap) }

    private fun exact(s: Segment, k: String): List<Any> = ArrayList<Any>().also { s.exact(k, it) }

    @Test
    fun exactPrefixFuzzyOverSmallCorpus() {
        withTempDir("seg") { dir ->
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
        }
    }

    @Test
    fun correctUnderATinyBlockCache() {
        withTempDir("seg") { dir ->
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
        }
    }

    @Test
    fun fuzzyMatchesWhenSomeTrigramsAreAbsent() {
        withTempDir("seg") { dir ->
            val s = seg(dir, listOf(entry("String", "String"), entry("StringBuilder", "StringBuilder"), entry("Integer", "Integer")))
            // "Strng" (typo, missing 'i') → grams str / trn / rng; only "str" exists in the corpus. The other
            // two must be skipped, leaving "String"/"StringBuilder" as candidates that the subsequence scorer keeps.
            val hits = fuzzy(s, "Strng").map { it.key }
            assertTrue("String" in hits, "expected String via the surviving trigram + scorer, got $hits")
            s.close()
        }
    }

    @Test
    fun prefixOnlySegmentFallsBackForFuzzy() {
        withTempDir("seg") { dir ->
            val s = seg(
                dir,
                listOf(entry("Apple", "Apple"), entry("Apricot", "Apricot"), entry("Banana", "Banana")),
                mode = MatchingMode.PREFIX_ONLY,
            )
            // No trigram index was built; fuzzy() must degrade to prefix semantics, not crash.
            assertEquals(listOf("Apple"), fuzzy(s, "App").map { it.key })
            assertEquals(listOf("Apple", "Apricot"), prefix(s, "Ap").map { it.key })
            s.close()
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
        withTempDir("segw") { dir ->
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
        withTempDir("seg") { dir ->
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
        }
    }

    @Test
    fun emptySegmentIsQueryable() {
        withTempDir("seg") { dir ->
            val s = seg(dir, emptyList())
            assertTrue(exact(s, "x").isEmpty())
            assertTrue(prefix(s, "x").isEmpty())
            assertTrue(fuzzy(s, "xyz").isEmpty())
            s.close()
        }
    }

    @Test
    fun camelHumpPatternsSurfaceThroughFuzzy() {
        withTempDir("seg") { dir ->
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
        }
    }

    @Test
    fun humpQueriesWorkWithoutATrigramIndex() {
        withTempDir("seg") { dir ->
            val s = seg(
                dir,
                listOf(entry("NullPointerException", "NPEv"), entry("Apple", "Apple")),
                mode = MatchingMode.PREFIX_ONLY,
            )
            // The window scan needs no trigram dictionary, so hump queries survive PREFIX_ONLY segments.
            assertTrue(fuzzy(s, "NPE").any { it.key == "NullPointerException" })
            s.close()
        }
    }

    @Test
    fun shortPatternsMatchCaseInsensitivelyThroughFuzzy() {
        withTempDir("seg") { dir ->
            val s = seg(dir, listOf(entry("String", "String"), entry("stack", "stack"), entry("List", "List")))
            // Below trigram length the fuzzy path used to degrade to the case-sensitive prefix scan;
            // the first-character window scan keeps it case-insensitive.
            val hits = fuzzy(s, "st").map { it.key }
            assertTrue("String" in hits && "stack" in hits, "expected both cases, got $hits")
            s.close()
        }
    }

    /**
     * The v2 format folds a uniform segment's origin into one footer byte (dropping the per-posting byte), and
     * falls back to the per-posting byte for a mixed corpus. Origin drives the score (`Scoring.originBonus`:
     * LIBRARY 12 > SDK 0), so a preserved 12-point gap proves the origin round-trips on both paths.
     */
    @Test
    fun originRoundTripsWhetherFoldedOrPerPosting() {
        withTempDir("seg") { dir ->
            // Uniform (single-origin) segments: origin lives once in the footer, yet still ranks each value.
            val libSeg = seg(Files.createDirectory(dir.resolve("lib")), listOf(entry("Foo", "Foo", IndexOrigin.LIBRARY)))
            val sdkSeg = seg(Files.createDirectory(dir.resolve("sdk")), listOf(entry("Foo", "Foo", IndexOrigin.SDK)))
            val libScore = prefix(libSeg, "Foo").single().score
            val sdkScore = prefix(sdkSeg, "Foo").single().score
            assertEquals(12, libScore - sdkScore, "the LIBRARY origin bonus must survive the per-segment fold")
            libSeg.close(); sdkSeg.close()

            // Mixed origins under one segment fall back to the per-posting byte, each preserved independently.
            val mixSeg = seg(
                Files.createDirectory(dir.resolve("mix")),
                listOf(entry("Foo", "lib", IndexOrigin.LIBRARY), entry("Foo", "sdk", IndexOrigin.SDK)),
            )
            val byValue = prefix(mixSeg, "Foo").associate { (it.value as String) to it.score }
            assertEquals(
                12, byValue.getValue("lib") - byValue.getValue("sdk"),
                "a mixed-origin segment must keep each posting's own origin",
            )
            mixSeg.close()
        }
    }

    /**
     * A query whose key can't fall inside the segment's [minTerm]..[maxTerm] range is answered from the
     * resident footer alone — zero block reads. This is the win at classpath scale, where a single query
     * would otherwise seek into hundreds of per-artifact segments that can't possibly match.
     */
    @Test
    fun outOfRangeQuerySkipsTheSegmentWithoutTouchingDisk() {
        withTempDir("seg") { dir ->
            val entries = (0 until 300).map { entry("Item%03d".format(it), "v$it", IndexOrigin.LIBRARY) }
            val cache = BlockCache(8L * 1024 * 1024)
            val s = seg(dir, entries, cache = cache) // range is Item000..Item299

            val before = cache.blockReads.get()
            assertTrue(exact(s, "ZZZ").isEmpty())        // past the max term
            assertTrue(prefix(s, "ZZZ").isEmpty())       // window sorts after everything
            assertTrue(prefix(s, "AAA").isEmpty())        // window sorts before everything
            assertEquals(before, cache.blockReads.get(), "an out-of-range query must not read any block")

            // Sanity: an in-range query DOES page from disk, so the counter is genuinely wired.
            assertEquals(1, exact(s, "Item150").size)
            assertTrue(cache.blockReads.get() > before, "an in-range query must actually read blocks")
            s.close()
        }
    }

    /**
     * The constant pool stores a string shared across values ONCE. Two segments hold 500 members each: one where
     * every member shares a single long owner FQN + signature, one where each owner is distinct. The shared-owner
     * segment must be dramatically smaller (the owner is pooled, not repeated 500×), and values round-trip intact.
     */
    @Test
    fun constantPoolDedupsStringsSharedAcrossValues() {
        withTempDir("seg") { dir ->
            val ext = MemberIndex()
            val owner = "com.example.some.deeply.nested.pkg.VeryLongOwnerTypeName"
            val shared = (0 until 500).map {
                IndexEntry("m$it", MemberValue("m$it", owner, "method", "(Ljava/lang/String;)V"), IndexOrigin.LIBRARY)
            }
            val sharedFile = dir.resolve("shared.seg"); Segment.write(sharedFile, ext, shared)

            val distinct = (0 until 500).map {
                val o = "com.example.some.deeply.nested.pkg.OwnerTypeNumber%05d".format(it)
                IndexEntry("m$it", MemberValue("m$it", o, "method", "(Ljava/lang/String;)V"), IndexOrigin.LIBRARY)
            }
            val distinctFile = dir.resolve("distinct.seg"); Segment.write(distinctFile, ext, distinct)

            // Owner (56 bytes) + signature + kind stored once vs 500× ⇒ the shared segment is well under half the
            // distinct one (which must repeat each unique owner in the pool).
            assertTrue(
                Files.size(sharedFile) * 2 < Files.size(distinctFile),
                "pooling must dedup the shared owner (shared=${Files.size(sharedFile)}, distinct=${Files.size(distinctFile)})",
            )

            // Values still round-trip exactly through the pool (a tiny block cache paging the pool region).
            val s = Segment.open(sharedFile, ext, BlockCache(maxBytes = 256, blockSize = 64), 0)
            try {
                val v = exact(s, "m42").single() as MemberValue
                assertEquals("m42", v.name)
                assertEquals(owner, v.owner)
                assertEquals("method", v.kind)
                assertEquals("(Ljava/lang/String;)V", v.signature)
            } finally { s.close() }
        }
    }
}
