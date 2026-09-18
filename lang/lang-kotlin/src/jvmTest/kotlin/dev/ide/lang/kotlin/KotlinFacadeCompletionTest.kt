package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Class-name completion must NOT offer Kotlin file/multi-file **facade** JVM classes — the synthetic
 * `…Kt` classes top-level functions/properties compile into (`ConsoleKt`, the multi-file `StringsKt`, its
 * `StringsKt__…` parts). The bytecode-name-only `java.classNames` index can't tell them from real types
 * (they're public classes), so [KotlinSymbolService] filters a `…Kt`-named BINARY class that has no
 * `kotlin.typeShape` entry (that index holds every real classpath type and excludes facades by construction).
 * A real type reached through the same index still completes — including one merely *named* `…Kt`.
 */
class KotlinFacadeCompletionTest {

    private fun names(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }

    @Test
    fun multiFileFacadeAndPartsAreHidden() {
        val items = names("package demo\nfun f() { Strings| }")
        assertFalse("StringsKt" in items, "multi-file facade StringsKt must not surface as a class; got ${items.take(20)}")
        assertFalse(items.any { "__" in it }, "multi-file facade parts (StringsKt__…) must not surface; got ${items.take(20)}")
    }

    @Test
    fun fileFacadeIsHidden() {
        assertFalse("ConsoleKt" in names("package demo\nfun f() { Conso| }"), "file facade ConsoleKt must not surface as a class")
    }

    @Test
    fun realClassStillCompletes() {
        assertTrue("Pair" in names("package demo\nfun f() { Pai| }"), "a real classpath type (Pair) must still complete")
    }

    @Test
    fun realTypeNamedLikeAFacadeIsKept() {
        // `HelperKt` is a real `class` (has a typeShape entry), not a facade — the name heuristic alone would
        // wrongly drop it, so the typeShape lookup must keep it.
        assertTrue("HelperKt" in names("package demo\nfun f() { Helpe| }"), "a real class merely named *Kt must still complete")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        private val CLASS_NAMES = IndexId("java.classNames")
        private val TYPE_SHAPE = IndexId("kotlin.typeShape")

        // What `java.classNames` would serve over the classpath: facades (no typeShape entry) AND real types
        // (Pair, and a real class named like a facade). Keyed by simple name, the index's key shape.
        private val classNames: Map<String, List<ClassNameValue>> = listOf(
            "kotlin.text.StringsKt",
            "kotlin.text.StringsKt__StringsKt",
            "kotlin.text.StringsKt__StringBuilderKt",
            "kotlin.io.ConsoleKt",
            "kotlin.Pair",
            "demo.HelperKt",
        ).groupBy({ it.substringAfterLast('.') }, { ClassNameValue(it, IndexOrigin.LIBRARY, "class") })

        // Only the REAL types have a member shape; facades are excluded from `kotlin.typeShape` by construction.
        private val shapedTypes = setOf("kotlin.Pair", "demo.HelperKt")

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when (id) {
                CLASS_NAMES -> classNames[key]?.asSequence()?.map { it as V } ?: emptySequence()
                TYPE_SHAPE -> if (key in shapedTypes) sequenceOf(TypeShape(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) as V) else emptySequence()
                else -> emptySequence()
            }

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == CLASS_NAMES)
                    classNames.asSequence()
                        .filter { it.key.startsWith(prefix, ignoreCase = true) }
                        .flatMap { (k, vs) -> vs.asSequence().map { Hit(k, it as V, 0) } }
                        .take(limit)
                else emptySequence()

            // The real engine's fuzzy is a superset of prefix (typeNamesByPrefix queries through it).
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
                prefix(id, pattern, limit)
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
    }
}
