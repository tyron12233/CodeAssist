package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.CallableShapeExternalizer
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `kotlin.callables` index — the persistent backing for classpath extensions + top-level callables.
 * Verifies the PRODUCER extracts tagged entries from real stdlib `@Metadata` facades, the codec ROUND-TRIPS
 * a shape, and the CONSUMER ([KotlinSymbolService] via the analyzer) resolves an stdlib extension and a
 * top-level callable through the index (not the in-memory scan fallback — this analyzer is index-wired).
 */
class KotlinCallableIndexTest {

    @Test
    fun producerEmitsTopLevelAndExtensionEntries() {
        assertTrue(KotlinCallableIndex.topKey("println") in served, "println must be a top-level entry; sample=${served.keys.take(8)}")
        assertTrue(
            served.keys.any { it.startsWith(KotlinCallableIndex.EXT_PREFIX) && it.endsWith(" trim") },
            "an `ext:<receiver> trim` entry expected; sample=${served.keys.filter { it.contains("trim") }.take(8)}",
        )
    }

    @Test
    fun codecRoundTripsAShape() {
        val key = served.keys.first { it.startsWith(KotlinCallableIndex.EXT_PREFIX) && it.endsWith(" trim") }
        val shape = served.getValue(key).first()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { CallableShapeExternalizer.write(it, shape) }
        val back = DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { CallableShapeExternalizer.read(it) }
        assertEquals(shape.name, back.name)
        assertEquals(shape.receiverFqn, back.receiverFqn)
        assertEquals(shape.signature, back.signature)
        assertEquals(shape.kind, back.kind)
    }

    @Test
    fun consumerResolvesExtensionThroughIndex() {
        // `"".trim()` / `"".uppercase()` — String extensions served only by the index here.
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", """fun f() { "hello".tri| }""") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("trim" in items, "String.trim extension via the callable index; got ${items.take(20)}")
    }

    @Test
    fun consumerResolvesTopLevelThroughIndex() {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { printl| }") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("println" in items, "top-level println via the callable index; got ${items.take(20)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        /** Real producer output over the stdlib jar's facade classes (`…Kt`), keyed by the index's tagged key. */
        private val served: Map<String, List<CallableShape>> = buildServed()

        private fun buildServed(): Map<String, List<CallableShape>> {
            val out = HashMap<String, MutableList<CallableShape>>()
            val jar = stdlibJarPath()
            ZipFile(jar.toFile()).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    // Top-level + extension callables live in the file/multi-file `…Kt` facades — scanning just
                    // those keeps the test fast while still covering println (ConsoleKt) and trim (StringsKt).
                    if (!e.name.endsWith("Kt.class")) continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    KotlinCallableIndex.index(FakeInput(e.name, bytes)).forEach { (k, v) ->
                        out.getOrPut(k) { ArrayList() }.addAll(v)
                    }
                }
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id == KotlinCallableIndex.id) served[key]?.asSequence()?.map { it as V } ?: emptySequence()
                else emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == KotlinCallableIndex.id)
                    served.asSequence()
                        .filter { it.key.startsWith(prefix) }
                        .flatMap { (k, vs) -> vs.asSequence().map { Hit(k, it as V, 0) } }
                        .take(limit)
                else emptySequence()

            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus()
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }

        private class FakeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: Path? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }
    }
}
