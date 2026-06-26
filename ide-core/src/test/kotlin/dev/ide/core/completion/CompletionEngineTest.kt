package dev.ide.core.completion

import dev.ide.lang.LanguageId
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.COMPLETION_WEIGHER_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.CompletionWeigher
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.patterns.DomPatterns
import dev.ide.platform.PluginId
import dev.ide.platform.ContentHash
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unified [CompletionEngine]: contributors add / filter / decorate / stop over one shared result set,
 * weighers rank the merged set, and pattern + language gating decides which contributors run.
 */
class CompletionEngineTest {

    private val kotlin = LanguageId("kotlin")

    private fun item(label: String, sort: Int = 0) =
        CompletionItem(label, label, CompletionItemKind.VARIABLE, sortPriority = sort)

    private fun contributor(cid: String, fill: suspend (CompletionParams, CompletionResultSet) -> Unit) =
        object : CompletionContributor {
            override val id = cid
            override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) =
                fill(params, result)
        }

    private fun params(position: DomNode? = N(NodeKind.NAME_REF, "f"), language: LanguageId = kotlin) =
        CompletionParams(
            document = Doc("f"),
            offset = 1,
            prefix = "f",
            language = language,
            trigger = CompletionTrigger.Explicit,
            replacementRange = TextRange(0, 1),
            position = position,
            parsedFile = null,
        )

    private fun engineWith(vararg contributions: CompletionContribution): Pair<CompletionEngine, ExtensionRegistryImpl> {
        val reg = ExtensionRegistryImpl()
        contributions.forEach { reg.register(COMPLETION_CONTRIBUTOR_EP, it, PluginId("test")) }
        return CompletionEngine(reg) to reg
    }

    @Test
    fun mergesItemsFromMultipleContributors() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("a") { _, r -> r.addElement(item("alpha")) }, order = 1),
            CompletionContribution(contributor("b") { _, r -> r.addElement(item("beta")) }, order = 2),
        )
        val labels = runBlocking { engine.complete(params()) }.items.map { it.label }
        assertTrue("alpha" in labels && "beta" in labels, "got $labels")
    }

    @Test
    fun laterContributorCanFilter() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("a") { _, r -> r.addAllElements(listOf(item("keep"), item("drop"))) }, order = 1),
            CompletionContribution(contributor("filter") { _, r -> r.removeIf { it.label == "drop" } }, order = 2),
        )
        val labels = runBlocking { engine.complete(params()) }.items.map { it.label }
        assertEquals(listOf("keep"), labels)
    }

    @Test
    fun laterContributorCanDecorate() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("a") { _, r -> r.addElement(item("x")) }, order = 1),
            CompletionContribution(contributor("deco") { _, r -> r.replaceAll { it.copy(insertText = it.insertText + "()") } }, order = 2),
        )
        val item = runBlocking { engine.complete(params()) }.items.single()
        assertEquals("x()", item.insertText)
    }

    @Test
    fun stopHereSkipsRemaining() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("a") { _, r -> r.addElement(item("a")); r.stopHere() }, order = 1),
            CompletionContribution(contributor("b") { _, r -> r.addElement(item("b")) }, order = 2),
        )
        val labels = runBlocking { engine.complete(params()) }.items.map { it.label }
        assertEquals(listOf("a"), labels)
    }

    @Test
    fun weigherControlsOrder() {
        val (engine, reg) = engineWith(
            CompletionContribution(contributor("a") { _, r -> r.addElement(item("low")); r.addElement(item("high")) }),
        )
        // A weigher that floats "high" to the top regardless of insertion order.
        reg.register(COMPLETION_WEIGHER_EP, object : CompletionWeigher {
            override val id = "test.boost"
            override val order = 0
            override fun weigh(item: CompletionItem, params: CompletionParams) = if (item.label == "high") 1.0 else 0.0
        }, PluginId("test"))
        val labels = runBlocking { engine.complete(params()) }.items.map { it.label }
        assertEquals(listOf("high", "low"), labels)
    }

    @Test
    fun sortPriorityWeigherPreservesLegacyOrder() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("a") { _, r ->
                r.addElement(item("late", sort = 5))
                r.addElement(item("early", sort = -2))
            }),
        )
        val labels = runBlocking { engine.complete(params()) }.items.map { it.label }
        assertEquals(listOf("early", "late"), labels) // lower sortPriority ranks first
    }

    @Test
    fun patternGatesContributors() {
        val onlyCalls = DomPatterns.call()
        val (engine, _) = engineWith(
            CompletionContribution(contributor("callOnly") { _, r -> r.addElement(item("called")) }, pattern = onlyCalls),
        )
        // position is a NAME_REF → the call-only contributor must not run.
        assertTrue(runBlocking { engine.complete(params(position = N(NodeKind.NAME_REF))) }.items.isEmpty())
        // position is a METHOD_CALL → it runs.
        assertEquals(listOf("called"), runBlocking { engine.complete(params(position = N(NodeKind.METHOD_CALL))) }.items.map { it.label })
    }

    @Test
    fun languageGatesContributors() {
        val (engine, _) = engineWith(
            CompletionContribution(contributor("xmlOnly") { _, r -> r.addElement(item("x")) }, languages = setOf(LanguageId("xml"))),
        )
        assertTrue(runBlocking { engine.complete(params(language = kotlin)) }.items.isEmpty())
        assertEquals(1, runBlocking { engine.complete(params(language = LanguageId("xml"))) }.items.size)
    }

    @Test
    fun nullPositionRunsLanguageMatchingContributors() {
        // When the file can't be parsed (position == null) patterns can't be evaluated, so language-matching
        // contributors still run (e.g. buffer words on a totally broken file).
        val (engine, _) = engineWith(
            CompletionContribution(contributor("any") { _, r -> r.addElement(item("w")) }, pattern = DomPatterns.call()),
        )
        assertEquals(listOf("w"), runBlocking { engine.complete(params(position = null)) }.items.map { it.label })
    }

    // --- minimal fakes ---

    private class N(
        override val kind: NodeKind,
        private val txt: String = "",
        override val parent: DomNode? = null,
    ) : DomNode {
        override val range = TextRange(0, txt.length)
        override val children = emptyList<DomNode>()
        override fun text(): CharSequence = txt
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = FakeFile
        override val version = 1L
        override fun length() = text.length
    }

    private object FakeFile : VirtualFile {
        override val path = "/f.kt"
        override val name = "f.kt"
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children() = emptyList<VirtualFile>()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
