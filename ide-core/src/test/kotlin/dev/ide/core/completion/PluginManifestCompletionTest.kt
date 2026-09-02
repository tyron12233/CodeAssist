package dev.ide.core.completion

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.TextRange
import dev.ide.platform.Disposable
import dev.ide.testkit.TestDocument
import dev.ide.testkit.virtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion in a plugin's packaged manifest. What matters is that the offers come from real data (the keys
 * the parser reads, the plugin ids the IDE loaded, the `Plugin` implementations the index knows) and that
 * the contributor keeps out of every other file, since it is registered against plain text.
 */
class PluginManifestCompletionTest {

    @Test
    fun `offers the manifest's keys at a key position`() {
        val labels = complete("[plugin]\nid = \"com.example.p\"\n", caretAfter = "id = \"com.example.p\"\n")
        // Exactly the keys PluginManifestToml reads, and none it ignores.
        assertTrue("entryPoints" in labels, labels.toString())
        assertTrue("minHostVersion" in labels, labels.toString())
        assertTrue("essential" !in labels, "the parser ignores 'essential', so offering it wastes an edit")
        assertTrue("trusted" !in labels, "the parser ignores 'trusted', so offering it wastes an edit")
    }

    @Test
    fun `a key inserts its assignment, ready for a value`() {
        val items = items("[plugin]\n", caretAfter = "[plugin]\n")
        val entryPoints = items.single { it.label == "entryPoints" }
        assertEquals("entryPoints = ", entryPoints.insertText)
    }

    @Test
    fun `dependsOn offers the plugin ids this IDE actually loaded`() {
        val labels = complete(
            "[plugin]\ndependsOn = [\"",
            caretAfter = "dependsOn = [\"",
            pluginIds = listOf("kotlin-language", "android-support"),
        )
        assertEquals(listOf("android-support", "kotlin-language"), labels.sorted())
    }

    @Test
    fun `entryPoints offers the Plugin implementations in the project`() {
        val labels = complete(
            "[plugin]\nentryPoints = [\"",
            caretAfter = "entryPoints = [\"",
            pluginClasses = listOf("com.example.OnePlugin", "com.example.TwoPlugin"),
        )
        assertEquals(listOf("com.example.OnePlugin", "com.example.TwoPlugin"), labels.sorted())
    }

    @Test
    fun `entryPoints offers nothing while the index is still building`() {
        // Claiming there are no Plugin classes would be worse than offering nothing at all.
        val labels = complete(
            "[plugin]\nentryPoints = [\"",
            caretAfter = "entryPoints = [\"",
            pluginClasses = listOf("com.example.OnePlugin"),
            indexReady = false,
        )
        assertEquals(emptyList(), labels)
    }

    @Test
    fun `apiVersion offers the version this IDE loads`() {
        val labels = complete("[plugin]\napiVersion = ", caretAfter = "apiVersion = ")
        assertEquals(listOf("1"), labels)
    }

    @Test
    fun `another plain-text file gets nothing`() {
        val labels = complete("[plugin]\n", caretAfter = "[plugin]\n", fileName = "notes.txt")
        assertEquals(emptyList(), labels)
    }

    // ---- harness ----------------------------------------------------------------------------------

    private fun complete(
        text: String,
        caretAfter: String,
        fileName: String = "codeassist_plugin.toml",
        pluginIds: List<String> = emptyList(),
        pluginClasses: List<String> = emptyList(),
        indexReady: Boolean = true,
    ): List<String> =
        items(text, caretAfter, fileName, pluginIds, pluginClasses, indexReady).map { it.label }

    private fun items(
        text: String,
        caretAfter: String,
        fileName: String = "codeassist_plugin.toml",
        pluginIds: List<String> = emptyList(),
        pluginClasses: List<String> = emptyList(),
        indexReady: Boolean = true,
    ) = runBlocking {
        val offset = text.indexOf(caretAfter).let {
            require(it >= 0) { "caret anchor '$caretAfter' not in the text" }
            it + caretAfter.length
        }
        val contributor = PluginManifestCompletion(
            knownPluginIds = { pluginIds },
            index = { FakeIndex(pluginClasses, indexReady) },
        )
        val params = CompletionParams(
            document = TestDocument(text, virtualFile("/w/app/src/main/res/raw/$fileName", text)),
            offset = offset,
            prefix = "",
            language = LanguageId("text"),
            trigger = CompletionTrigger.Explicit,
            replacementRange = TextRange(offset, offset),
            position = null,
            parsedFile = null,
        )
        val collected = CollectingResultSet(params)
        contributor.fillCompletionVariants(params, collected)
        collected.elements
    }

    /** Serves the subtype index the contributor reads, and nothing else. */
    private class FakeIndex(private val pluginClasses: List<String>, ready: Boolean) : IndexService {
        override val status = IndexStatus(ready = ready)

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> {
            if (id !in SubtypeIndex.ALL || key != SubtypeIndex.key("dev.ide.plugin.Plugin")) return emptySequence()
            // One producer serves them; the others return nothing, as a real split index would.
            if (id != SubtypeIndex.KOTLIN_SOURCE) return emptySequence()
            return pluginClasses
                .map { SubtypeValue(fqn = it, kind = "class", supertype = "dev.ide.plugin.Plugin") as V }
                .asSequence()
        }

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int) = emptySequence<dev.ide.index.Hit<V>>()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int) = emptySequence<dev.ide.index.Hit<V>>()
        override suspend fun ensureUpToDate(scope: IndexScope) = Unit
        override suspend fun reindexSource(path: Path, text: String) = Unit
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private class CollectingResultSet(override val params: CompletionParams) : dev.ide.lang.completion.CompletionResultSet {
        private val items = ArrayList<dev.ide.lang.completion.CompletionItem>()
        override val prefix get() = params.prefix
        override val elements: List<dev.ide.lang.completion.CompletionItem> get() = items
        override fun addElement(item: dev.ide.lang.completion.CompletionItem) { items.add(item) }
        override fun addAllElements(items: Iterable<dev.ide.lang.completion.CompletionItem>) { this.items.addAll(items) }
        override fun removeIf(predicate: (dev.ide.lang.completion.CompletionItem) -> Boolean) { items.removeAll(predicate) }
        override fun replaceAll(transform: (dev.ide.lang.completion.CompletionItem) -> dev.ide.lang.completion.CompletionItem) {
            val mapped = items.map(transform)
            items.clear()
            items.addAll(mapped)
        }
        override fun stopHere() = Unit
        override val isStopped = false
        override fun markIncomplete() = Unit
        override fun setReplacementRange(range: TextRange) = Unit
    }
}
