package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `kotlin.builtinCallables` index — the TOP-LEVEL array-factory INTRINSICS (`arrayOf`, `intArrayOf`,
 * `charArrayOf`, `emptyArray`, …). These are declared in the stdlib's `.kotlin_builtins` protobuf fragments,
 * NOT as `.class` files, so the `.class`-scanning `kotlin.callables` index never carried them and they were
 * missing from completion (see [KotlinBuiltinCallableIndex]). Verifies the PRODUCER extracts them as top-level
 * entries and the CONSUMER ([KotlinSymbolService] via the analyzer) completes one through the index.
 */
class KotlinBuiltinCallableTest {

    @Test
    fun producerEmitsArrayFactoryIntrinsics() {
        // Each is a top-level (no-receiver) intrinsic → a `top:` key; none has a `.class` facade.
        for (name in listOf("arrayOf", "intArrayOf", "charArrayOf", "booleanArrayOf", "longArrayOf", "doubleArrayOf")) {
            assertTrue(
                KotlinCallableIndex.topKey(name) in served,
                "$name must be indexed as a top-level builtin callable; sample=${served.keys.take(12)}",
            )
        }
    }

    @Test
    fun consumerCompletesIntArrayOfThroughIndex() {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { val a = intArrayO| }") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("intArrayOf" in items, "top-level intArrayOf via the builtin-callable index; got ${items.take(20)}")
    }

    @Test
    fun consumerCompletesArrayOfThroughIndex() {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { val a = arrayO| }") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("arrayOf" in items, "top-level arrayOf via the builtin-callable index; got ${items.take(20)}")
    }

    @Test
    fun emptyArrayNotDuplicatedAcrossIndices() {
        // `emptyArray` is the lone overlap: a real `ArrayIntrinsicsKt.class` facade (kotlin.callables) AND a
        // package function in `kotlin.kotlin_builtins` (kotlin.builtinCallables). With BOTH indices live it
        // must still appear exactly once — the completion dedup (name#kind#signature) folds the twin.
        val count = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { val a = emptyArra| }") }
            .items.count { it.symbol?.name == "emptyArray" }
        assertEquals(1, count, "emptyArray must not be duplicated across the two callable indices")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        /** Real producer output over the stdlib jar's `.kotlin_builtins` fragments, keyed by the tagged key. */
        private val served: Map<String, List<CallableShape>> = buildServed(".kotlin_builtins") { n, b ->
            KotlinBuiltinCallableIndex.index(FakeInput(n, b))
        }

        /** The `.class`-facade side (`kotlin.callables`), so the dedup across BOTH indices is exercised. */
        private val servedFacades: Map<String, List<CallableShape>> = buildServed("Kt.class") { n, b ->
            KotlinCallableIndex.index(FakeInput(n, b))
        }

        private fun buildServed(suffix: String, index: (String, ByteArray) -> Map<String, Collection<CallableShape>>): Map<String, List<CallableShape>> {
            val out = HashMap<String, MutableList<CallableShape>>()
            ZipFile(stdlibJarPath().toFile()).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(suffix)) continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    index(e.name, bytes).forEach { (k, v) -> out.getOrPut(k) { ArrayList() }.addAll(v) }
                }
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            private fun tableFor(id: IndexId): Map<String, List<CallableShape>>? = when (id) {
                KotlinBuiltinCallableIndex.id -> served
                KotlinCallableIndex.id -> servedFacades
                else -> null
            }

            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                tableFor(id)?.get(key)?.asSequence()?.map { it as V } ?: emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                tableFor(id)?.asSequence()
                    ?.filter { it.key.startsWith(prefix) }
                    ?.flatMap { (k, vs) -> vs.asSequence().map { Hit(k, it as V, 0) } }
                    ?.take(limit)
                    ?: emptySequence()

            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
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
