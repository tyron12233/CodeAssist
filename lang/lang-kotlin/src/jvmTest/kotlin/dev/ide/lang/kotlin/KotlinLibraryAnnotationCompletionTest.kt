package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Annotation-NAME completion (`@Comp…`) must offer LIBRARY (classpath) annotations, not only source ones.
 * The `@` position restricts candidates to annotation classes; a classpath type reaches that filter with the
 * [dev.ide.lang.kotlin.symbols.KotlinSymbolService.classNameKind]-mapped kind from the `java.classNames`
 * index. Before the index labeled binary annotations `"annotation"` (it hard-coded `"class"` for every
 * `.class`), `@Composable`/`@Deprecated`-style library annotations arrived as `CLASS`, were filtered out, and
 * the popup came back empty — the reported bug. Here the fake index serves a binary annotation with its real
 * kind, so the annotation surfaces while an equally-prefixed binary class is correctly excluded at `@` yet
 * kept at an ordinary type position.
 */
class KotlinLibraryAnnotationCompletionTest {

    private fun names(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }

    @Test
    fun libraryAnnotationSurfacesAtAtSign() {
        val ns = names("package demo\n@Marker| class Foo")
        assertTrue("Marker" in ns, "a classpath annotation must complete at `@…`; got $ns")
    }

    @Test
    fun nonAnnotationLibraryClassIsExcludedAtAtSign() {
        // `MarkerWidget` shares the `Marker` prefix but is a plain class -> the `@` filter must drop it.
        val ns = names("package demo\n@Marker| class Foo")
        assertFalse("MarkerWidget" in ns, "a non-annotation classpath class must NOT appear at `@…`; got $ns")
    }

    @Test
    fun bothAppearAtOrdinaryTypePosition() {
        // The annotation-only filter is specific to `@`; a normal type slot offers both.
        val ns = names("package demo\nfun f() { val x: Marker| }")
        assertTrue("Marker" in ns, "the annotation is still a usable type; got $ns")
        assertTrue("MarkerWidget" in ns, "the class is a usable type; got $ns")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        private val CLASS_NAMES = IndexId("java.classNames")

        // What `java.classNames` serves over the classpath: a binary ANNOTATION and a binary CLASS that share a
        // prefix, keyed by simple name (the index's key shape). The kind string is exactly what the real index
        // producer now derives from the ASM access flags.
        private val classNames: Map<String, List<ClassNameValue>> = mapOf(
            "Marker" to listOf(ClassNameValue("demo.anno.Marker", IndexOrigin.LIBRARY, "annotation")),
            "MarkerWidget" to listOf(ClassNameValue("demo.widget.MarkerWidget", IndexOrigin.LIBRARY, "class")),
        )

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id == CLASS_NAMES) classNames[key]?.asSequence()?.map { it as V } ?: emptySequence() else emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == CLASS_NAMES)
                    classNames.asSequence()
                        .filter { it.key.startsWith(prefix, ignoreCase = true) }
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
    }
}
