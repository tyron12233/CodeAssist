package dev.ide.lang.jdt

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.completion.CompletionResult
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QOL: completion shows inferred generics (a member of a parameterized receiver renders its substituted
 * type arguments), and an unimported type whose simple name collides with an explicit import is inserted
 * fully-qualified instead of adding a duplicate import.
 */
class GenericsImportReproTest {

    private class FakeIndex(val classNames: List<ClassNameValue>) : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
            if (id.value == "java.classNames")
                classNames.filter { it.fqn.substringAfterLast('.').startsWith(pattern, ignoreCase = true) }
                    .asSequence().map { Hit(it.fqn.substringAfterLast('.'), it as V, 800) }
            else emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus()
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable {}
    }

    private fun result(code: String, index: IndexService? = null): CompletionResult {
        val (analyzer, dir) = workspaceWith()
        if (index != null) analyzer.indexService = index
        return try {
            completeResult(analyzer, dir.resolve("app/T.java"), code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun memberReturnTypeShowsInferredGenerics() {
        val stream = result("package app; class T { void m() { java.util.List.of(1).|CARET| } }")
            .items.firstOrNull { it.label.startsWith("stream(") }
        assertNotNull(stream, "expected a stream() candidate")
        assertEquals("Stream<Integer>", stream.detail, "return type should carry inferred generics")
    }

    @Test
    fun methodParameterShowsInferredGenerics() {
        val map = result("package app; class T { void m() { java.util.List.of(1).stream().|CARET| } }")
            .items.firstOrNull { it.label.startsWith("map(") }
        assertNotNull(map, "expected a map() candidate")
        // Depending on whether the JDK image carries method parameter names, the label may end with a
        // parameter name ("map(Function<Integer,R> mapper)") or without one ("map(Function<Integer,R>)").
        // The assertion is about the substituted generics (T -> Integer, simplified wildcards), so accept either.
        assertTrue(
            map.label.startsWith("map(Function<Integer,R>"),
            "parameter should show substituted T=Integer with simplified wildcards: ${map.label}",
        )
        assertEquals("Stream<R>", map.detail, "return type should carry the method's own type variable: ${map.detail}")
    }

    @Test
    fun collidingUnimportedTypeIsInsertedFullyQualifiedWithoutImport() {
        val index = FakeIndex(listOf(ClassNameValue("java.util.List", IndexOrigin.SDK, "interface")))
        val item = result("package app; import java.awt.List; class T { void m() { Lis|CARET| } }", index)
            .items.firstOrNull { it.container == "java.util" }
        assertNotNull(item, "expected the unimported java.util.List candidate")
        assertEquals("java.util.List", item.insertText, "must insert the FQN to avoid a duplicate-import clash")
        assertTrue(item.additionalEdits.isEmpty(), "must NOT add an import that would clash: ${item.additionalEdits.map { it.newText }}")
    }

    @Test
    fun nonCollidingUnimportedTypeStillAutoImports() {
        val index = FakeIndex(listOf(ClassNameValue("java.util.List", IndexOrigin.SDK, "interface")))
        val item = result("package app; class T { void m() { Lis|CARET| } }", index)
            .items.firstOrNull { it.insertText == "List" }
        assertNotNull(item, "expected the unimported List candidate")
        assertTrue(item.additionalEdits.any { it.newText.contains("import java.util.List;") }, "should auto-import when no collision")
    }
}
