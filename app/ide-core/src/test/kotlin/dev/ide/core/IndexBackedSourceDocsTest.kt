package dev.ide.core

import dev.ide.core.analysis.IndexBackedSourceDocs
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Kotlin library's KDoc reaches the quick-doc popup with its markup (Markdown, fenced samples, `@param`),
 * straight from the index, while the completion and signature panels keep getting the plain text they always
 * have. Before, the index stored only cleaned text and the raw path read Java sources alone, so every Kotlin
 * library doc rendered as plain paragraphs.
 */
class IndexBackedSourceDocsTest {

    private val source = """
        package androidx.activity.compose

        import androidx.activity.ComponentActivity

        /**
         * Composes the given composable into the given activity.
         *
         * ```
         * setContent {
         *     MyComposableContent()
         * }
         * ```
         *
         * @param parent The parent composition reference
         * @param content A `@Composable` function declaring the UI contents
         */
        public fun ComponentActivity.setContent(parent: Any? = null, content: () -> Unit) {}
    """.trimIndent()

    private val docs = IndexBackedSourceDocs(FakeIndex(KotlinSourceDocIndex.index(Input(source))))
    private val owner = "androidx.activity.compose.ComponentActivityKt"

    @Test
    fun theRawDocKeepsItsMarkup() {
        val raw = assertNotNull(docs.methodRaw(owner, "setContent", 2))
        assertTrue("@param content" in raw, raw)
        assertTrue("```" in raw && "    MyComposableContent()" in raw, "the sample and its indentation: $raw")
        assertFalse(raw.lines().any { it.trimStart().startsWith("*") }, "no comment decoration left: $raw")
    }

    @Test
    fun thePlainDocIsCleanedAsBefore() {
        val plain = assertNotNull(docs.method(owner, "setContent", 2)?.doc)
        assertTrue(plain.startsWith("Composes the given composable"), plain)
        assertFalse("@param" in plain, "block tags stay out of the plain panels: $plain")
    }

    private class Input(private val src: String) : IndexInput {
        override val unitName = "androidx/activity/compose/ComponentActivity.kt"
        override val origin = IndexOrigin.LIBRARY_SOURCE
        override val contentHash = ContentHash("")
        override val sourcePath: String? = null
        override fun bytes() = src.toByteArray()
        override fun text() = src
        override fun dom() = null
    }

    /** Answers exact lookups on `kotlin.sourceDoc` from one file's index output; nothing else. */
    private class FakeIndex(private val byKey: Map<String, Collection<Any>>) : IndexService {
        override val status = IndexStatus()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable {}
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (id == KotlinSourceDocIndex.id) (byKey[key].orEmpty() as Collection<V>).asSequence() else emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
    }
}
