package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The direct-inheritor QUERY layer ([KotlinSymbolService.directInheritors] / [KotlinSymbolService.allInheritors])
 * — the CONSUMER of the already-built [SubtypeIndex] producers. Feeds a fake index from the real
 * [KotlinSourceSubtypeIndex] producer's output and verifies the family merge, the short-name-key filtering
 * (resolved FQNs don't leak across homonymous supertypes), and the transitive closure.
 */
class KotlinInheritorsQueryTest {

    @Test
    fun directInheritorsReturnsEverySubclassOfASealedHierarchy() {
        val svc = serviceOver(
            """
            package demo
            sealed class Expr
            class Add : Expr()
            class Neg : Expr()
            object Zero : Expr()
            """.trimIndent(),
        )
        assertEquals(
            setOf("demo.Add", "demo.Neg", "demo.Zero"),
            svc.directInheritors("demo.Expr").map { it.fqn }.toSet(),
        )
    }

    @Test
    fun allInheritorsWalksTheClosureTransitively() {
        val svc = serviceOver(
            """
            package demo
            sealed class Expr
            sealed class Bin : Expr()
            class BinAdd : Bin()
            class Lit : Expr()
            """.trimIndent(),
        )
        assertEquals(setOf("demo.BinAdd"), svc.directInheritors("demo.Bin").map { it.fqn }.toSet())
        assertEquals(
            setOf("demo.Bin", "demo.BinAdd", "demo.Lit"),
            svc.allInheritors("demo.Expr").map { it.fqn }.toSet(),
        )
    }

    @Test
    fun aResolvedSupertypeDoesNotLeakToAHomonymInAnotherPackage() {
        // `Impl : Base()` with `import other.Base` → the producer resolves the supertype to `other.Base`.
        val svc = serviceOver("package demo\nimport other.Base\nclass Impl : Base()\n")
        assertTrue(svc.directInheritors("other.Base").any { it.fqn == "demo.Impl" }, "resolved FQN matches")
        assertTrue(
            svc.directInheritors("demo.Base").none { it.fqn == "demo.Impl" },
            "a same-short-name `demo.Base` must NOT match the fully-resolved `other.Base`",
        )
    }

    // ---- harness ----

    private fun serviceOver(src: String): KotlinSymbolService {
        val served = mapOf<IndexId, Map<String, Collection<SubtypeValue>>>(
            SubtypeIndex.KOTLIN_SOURCE to KotlinSourceSubtypeIndex.index(SrcInput("S.kt", src)),
        )
        return KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = fakeIndex(served))
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeIndex(served: Map<IndexId, Map<String, Collection<SubtypeValue>>>) = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            served[id]?.get(key)?.asSequence()?.map { it as V } ?: emptySequence()

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private class SrcInput(override val unitName: String, private val text: String) : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("")
        override val sourcePath: Path = Paths.get("/virtual/$unitName")
        override fun bytes() = text.toByteArray()
        override fun text() = text
        override fun dom() = null
    }
}
