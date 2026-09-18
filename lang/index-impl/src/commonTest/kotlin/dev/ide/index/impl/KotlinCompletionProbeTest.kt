package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.platform.ContentHash
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole read path, end to end, on whichever platform this runs on: a real jar becomes a real index, and a
 * prefix answers with real Kotlin callables.
 *
 * Every piece of this has its own tests, and passing them separately is not the same claim. The zip reader is
 * checked against `java.util.zip`, the `@kotlin.Metadata` decoder against `kotlin-metadata-jvm`, the symbol
 * layer against the index's own, and the segment format against its bytes — each in isolation, each on one
 * side of a seam. What none of them says is whether the seams line up: whether a jar opened by the portable
 * archive reader yields entries the portable decoder understands, producing symbols the portable index
 * accepts, written to a segment the portable reader can query back. That is four modules and five formats
 * agreeing, and the only way to find out is to run it.
 *
 * It is NOT the editor's completion. There is no caret, no scope, no resolution, no ranking against the
 * surrounding code — that lives in `:lang-kotlin`, and runs on every target too (see
 * `KotlinEditorOffTheJvmTest`). This is the DATA path under it: given a prefix, what does the classpath
 * offer. Everything a completion list is built from, and none of the building.
 */
class KotlinCompletionProbeTest {

    /** A `.class` entry out of the fixture jar, in the shape an index extension consumes. */
    private class ClassEntry(
        override val unitName: String,
        private val hash: ContentHash,
        private val bytes: ByteArray,
    ) : IndexInput {
        override val origin = IndexOrigin.LIBRARY
        override val contentHash get() = hash
        override val sourcePath: String? = null
        override fun bytes(): ByteArray = bytes
        override fun text(): String? = null
        override fun dom(): ParsedFile? = null
    }

    /**
     * Index the fixture jar into a segment and open it, exactly as the engine would: read the archive, hand
     * every `.class` to the extension, write what comes back, reopen from disk.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun indexTheFixtureClasspath(): Segment {
        val archive = ZipArchive.open(Base64.decode(ClasspathFixture.JAR_BASE64))
        assertTrue(archive != null, "the fixture jar must open")

        val hash = ContentHash("probe-fixture")
        val entries = ArrayList<IndexEntry>()
        for (entry in archive.entries) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            val bytes = archive.read(entry) ?: continue
            val input = ClassEntry(entry.name, hash, bytes)
            if (!KotlinCallableIndex.inputFilter.accepts(input)) continue
            for ((key, values) in KotlinCallableIndex.index(input)) {
                for (value in values) entries.add(IndexEntry(key, value, IndexOrigin.LIBRARY))
            }
        }
        assertTrue(entries.isNotEmpty(), "the fixture classpath produced no callables at all")

        val path = scratchPath("probe.seg")
        writeSegment(path, KotlinCallableIndex, entries)
        return Segment.open(path, KotlinCallableIndex, BlockCache(maxBytes = 64 * 1024, blockSize = 4096), 0)
    }

    /** What a completion list would be built from: the callables whose name starts with [prefix]. */
    private fun Segment.topLevelStartingWith(prefix: String, cap: Int = 200): List<String> {
        val hits = ArrayList<Hit<Any>>()
        prefix(KotlinCallableIndex.topKey(prefix), hits, cap)
        return hits.mapNotNull { (it.value as? CallableShape)?.name }.distinct().sorted()
    }

    /** The extensions a receiver offers, which is what `expr.` has to answer. */
    private fun Segment.extensionsOn(receiverFqn: String, prefix: String = "", cap: Int = 200): List<String> {
        val hits = ArrayList<Hit<Any>>()
        prefix(KotlinCallableIndex.extPrefix(receiverFqn, prefix), hits, cap)
        return hits.mapNotNull { (it.value as? CallableShape)?.name }.distinct().sorted()
    }

    @Test
    fun aRealJarBecomesARealIndex() {
        val segment = indexTheFixtureClasspath()
        try {
            // `println` is the completion everyone would try first, and it is a top-level callable in
            // kotlin/io/ConsoleKt whose signature only exists in the class's @kotlin.Metadata.
            assertEquals(listOf("print", "println"), segment.topLevelStartingWith("print"))

            // And the prefix really is a prefix: `printl` drops `print` itself.
            assertEquals(listOf("println"), segment.topLevelStartingWith("printl"))
            assertEquals(listOf("readLine", "readln", "readlnOrNull"), segment.topLevelStartingWith("read"))
        } finally {
            segment.close()
        }
    }

    @Test
    fun theScopeFunctionsLandOnTheirReceiverAndNotInTheTopLevelBucket() {
        val segment = indexTheFixtureClasspath()
        try {
            // `let`, `apply`, `also` and friends are declared `fun <T> T.let(...)` — extensions whose
            // receiver is a TYPE VARIABLE. They are only reachable by receiver at all if the decoder read
            // the parameter AND resolved it to its bound, and getting that wrong is invisible: they would
            // simply be missing from `expr.` while everything else still worked.
            assertEquals(
                listOf("also", "apply", "let", "run", "takeIf", "takeUnless", "to"),
                segment.extensionsOn("kotlin.Any"),
            )

            // `run` and `with` also exist WITHOUT a receiver — `run { }` and `with(x) { }` — and the two
            // spellings are different callables that have to end up in different buckets.
            val topLevel = segment.topLevelStartingWith("")
            assertTrue("with" in topLevel, "with is top-level only; got $topLevel")
            assertTrue("run" in topLevel, "run is BOTH top-level and an extension")
            assertTrue("repeat" in topLevel)
            assertTrue("let" !in topLevel, "let is an extension, and must not also be offered bare")
        } finally {
            segment.close()
        }
    }

    @Test
    fun anExtensionIsFoundByItsReceiver() {
        // `1.toByte().and(…)`: an extension on kotlin.Byte, which is what a receiver-keyed query answers and
        // the whole reason the index keys extensions by receiver rather than by name alone.
        val segment = indexTheFixtureClasspath()
        try {
            assertEquals(listOf("and", "inv", "or", "xor"), segment.extensionsOn("kotlin.Byte"))

            // ...and narrowing by name prefix narrows the answer rather than returning the whole receiver.
            assertEquals(listOf("inv"), segment.extensionsOn("kotlin.Byte", "in"))

            // The receiver is part of the key, so the same four names on kotlin.Short are a separate bucket
            // and neither leaks into the other. That is what makes `expr.` answerable without resolving
            // every extension on the classpath.
            assertEquals(listOf("and", "inv", "or", "xor"), segment.extensionsOn("kotlin.Short"))
            assertEquals(emptyList(), segment.extensionsOn("kotlin.Int"), "nothing on this fixture extends Int")
        } finally {
            segment.close()
        }
    }

    @Test
    fun aPrefixThatMatchesNothingReturnsNothing() {
        // The negative half, and the one that catches a query walking off the end of the term dictionary:
        // a miss has to be empty, not the whole index and not a crash.
        val segment = indexTheFixtureClasspath()
        try {
            assertEquals(emptyList(), segment.topLevelStartingWith("zzzNoSuchCallable"))
            assertEquals(emptyList(), segment.extensionsOn("com.example.NotOnThisClasspath"))
        } finally {
            segment.close()
        }
    }

    @Test
    fun aCandidateCarriesWhatACompletionListWouldShow() {
        // A name alone is not a completion. The shape has to survive the round trip through the segment's
        // value codec, or the list renders `println` with no signature and inserts the wrong thing.
        val segment = indexTheFixtureClasspath()
        try {
            val hits = ArrayList<Hit<Any>>()
            segment.prefix(KotlinCallableIndex.topKey("println"), hits, 50)
            val shapes = hits.mapNotNull { it.value as? CallableShape }
            assertTrue(shapes.isNotEmpty(), "println must come back as a shape, not just a key")

            val overloads = shapes.filter { it.name == "println" }
            assertEquals(11, overloads.size, "every println overload in ConsoleKt must survive, not just one")
            assertTrue(
                overloads.all { it.packageName == "kotlin.io" },
                "each one has to know its package, or an import cannot be written",
            )
        } finally {
            segment.close()
        }
    }
}
