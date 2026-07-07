package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `kotlin.callables.source` — the project-source side of the callable index. The producer extracts
 * top-level/extension callables from a PSI parse with static receiver resolution (imports, builtins,
 * same-package), and the consumer surfaces a cross-file source extension WITHOUT the in-memory source
 * model covering its file (the 1.2 exit criterion), plus receiver-blind `name:` import candidates.
 */
class KotlinSourceCallableIndexTest {

    private fun produced(text: String, name: String = "Ext.kt"): Map<String, Collection<CallableShape>> =
        KotlinSourceCallableIndex.index(SourceInput(name, text))

    @Test
    fun producerEmitsTopLevelAndExtensionKeys() {
        val out = produced(
            """
            package demo
            fun greet() {}
            fun String.shout() {}
            val Int.doubled: Int get() = this * 2
            """.trimIndent(),
        )
        assertTrue(KotlinCallableIndex.topKey("greet") in out, "top-level greet; got ${out.keys}")
        assertTrue(KotlinCallableIndex.extKey("kotlin.String", "shout") in out, "String resolves via builtins; got ${out.keys}")
        assertTrue(KotlinCallableIndex.extKey("kotlin.Int", "doubled") in out, "an extension property indexes too; got ${out.keys}")
        assertTrue(KotlinCallableIndex.nameKey("shout") in out, "receiver-blind name: key; got ${out.keys}")
    }

    @Test
    fun producerResolvesReceiverThroughImportsAndPackage() {
        val out = produced(
            """
            package demo
            import other.Widget
            fun Widget.paint() {}
            fun Box.fill() {}
            """.trimIndent(),
        )
        assertTrue(KotlinCallableIndex.extKey("other.Widget", "paint") in out, "imported receiver; got ${out.keys}")
        assertTrue(KotlinCallableIndex.extKey("demo.Box", "fill") in out, "same-package receiver; got ${out.keys}")
    }

    @Test
    fun producerKeysTypeParamReceiverUnderItsBound() {
        val out = produced("package demo\nfun <T> T.alsoLog(): T = this\nfun <T : CharSequence> T.shrink() {}\n")
        assertTrue(KotlinCallableIndex.extKey("kotlin.Any", "alsoLog") in out, "unbounded T keys under Any; got ${out.keys}")
        assertTrue(KotlinCallableIndex.extKey("kotlin.CharSequence", "shrink") in out, "bounded T keys under the bound; got ${out.keys}")
        assertEquals("T", out.getValue(KotlinCallableIndex.extKey("kotlin.Any", "alsoLog")).first().receiverTypeParam)
    }

    @Test
    fun producerSkipsPrivateCallables() {
        val out = produced("package demo\nprivate fun secret() {}\nprivate fun String.hide() {}\n")
        assertTrue(out.isEmpty(), "private callables must not be indexed; got ${out.keys}")
    }

    @Test
    fun crossFileSourceExtensionCompletesThroughTheIndexAlone() {
        // The extension lives OUTSIDE the analyzer's source roots, so the in-memory model can never see it:
        // the completion below can only come from the source-callable index.
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", """fun f() { "x".sho| }""") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("shout" in items, "cross-file source extension via the index; got ${items.take(20)}")
    }

    @Test
    fun crossFileTopLevelCompletesThroughTheIndexAlone() {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { gree| }") }
            .items.mapNotNull { it.symbol?.name }
        assertTrue("greet" in items, "cross-file top-level via the index; got ${items.take(20)}")
    }

    @Test
    fun unresolvedExtensionGetsAnImportCandidateThroughNameKeys() {
        val service = dev.ide.lang.kotlin.symbols.KotlinSymbolService(
            listOf(DiskFile(srcDir)), listOf(stdlibJarPath()), fakeIndex,
        )
        val candidates = service.importCandidates("shout")
        assertTrue("ext.shout" in candidates, "receiver-blind name: key yields the import; got $candidates")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        /** What the source index serves for a file OUTSIDE [srcDir] (so only the index can supply it). */
        private val served: Map<String, List<CallableShape>> = HashMap<String, List<CallableShape>>().apply {
            val out = KotlinSourceCallableIndex.index(
                SourceInput("Elsewhere.kt", "package ext\nfun greet() {}\nfun String.shout() {}\n"),
            )
            out.forEach { (k, v) -> put(k, v.toList()) }
        }

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id == KotlinSourceCallableIndex.id) served[key]?.asSequence()?.map { it as V } ?: emptySequence()
                else emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == KotlinSourceCallableIndex.id)
                    served.asSequence()
                        .filter { it.key.startsWith(prefix) }
                        .flatMap { (k, vs) -> vs.asSequence().map { Hit(k, it as V, 0) } }
                        .take(limit)
                else emptySequence()

            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
                prefix(id, pattern, limit)

            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }

        private class SourceInput(override val unitName: String, private val text: String) : IndexInput {
            override val origin = IndexOrigin.SOURCE
            override val contentHash = ContentHash("")
            override val sourcePath: Path = Paths.get("/virtual/$unitName")
            override fun bytes() = text.toByteArray()
            override fun text() = text
            override fun dom() = null
        }
    }
}
