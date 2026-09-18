package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `kt.abstractNotImplemented` must NOT count a supertype's nested abstract TYPE (a nested `interface` /
 * `abstract class` — surfaced from bytecode as a `CLASS`-kind member carrying `ABSTRACT`, e.g.
 * `android.app.Activity.ScreenCaptureCallback`) as a member the subclass must implement. A real abstract
 * METHOD/property must still be required.
 */
class KotlinNestedAbstractTypeTest {

    private fun diag(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun nestedAbstractTypeIsNotRequiredImplementation() {
        // `lib.Base` has ONLY a nested abstract type `Callback` (the ScreenCaptureCallback shape) — a concrete
        // subclass must NOT be told to implement it.
        val d = diag("import lib.Base\nclass Impl : Base()")
        assertTrue(d.none { it.code == "kt.abstractNotImplemented" }, "a nested abstract TYPE must not be a 'must implement' member; got $d")
    }

    @Test
    fun realAbstractMethodIsStillRequired() {
        // `lib.Base2` adds a genuine abstract method `doWork` alongside the nested type — that one must flag.
        val d = diag("import lib.Base2\nclass Impl2 : Base2()")
        assertTrue(
            d.any { it.code == "kt.abstractNotImplemented" && it.message.contains("doWork") },
            "a real unimplemented abstract method must still flag; got $d",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val BIN = SymbolOrigin(false, null)
        private fun nestedType() = KotlinSymbol("Callback", SymbolKind.CLASS, origin = BIN, modifiers = setOf(Modifier.ABSTRACT, Modifier.STATIC))
        private fun abstractMethod(name: String) = KotlinSymbol(name, SymbolKind.METHOD, origin = BIN, modifiers = setOf(Modifier.ABSTRACT), signature = "(): Unit")

        private val served: Map<String, TypeShape> = mapOf(
            "lib.Base" to TypeShape(emptyList(), emptyList(), emptyList(), emptyList(), listOf(nestedType()), isKotlin = false),
            "lib.Base2" to TypeShape(emptyList(), emptyList(), emptyList(), emptyList(), listOf(nestedType(), abstractMethod("doWork")), isKotlin = false),
        )

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id.value == "kotlin.typeShape") served[key]?.let { sequenceOf(it as V) } ?: emptySequence() else emptySequence()
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
            .apply { indexService = fakeIndex }
    }
}
