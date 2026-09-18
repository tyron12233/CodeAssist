package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The user report: a `companion object` in the SAME live buffer being edited. `Test.` must offer `Companion`
 * (and the companion's members), and a bare companion member used unqualified inside the class must resolve
 * AND be semantically highlighted (`rainbowColors.forEach()` inside a member function).
 */
class KotlinCompanionSameFileTest {

    // class Test { companion object { val rainbowColors = "" } <body> }
    private val head = "class Test {\n  companion object {\n    val rainbowColors = \"\"\n  }\n"

    private fun names(code: String): List<String> {
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        return runBlocking { analyzer.completeAtCaret(srcDir, "Test.kt", code) }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun classReferenceOffersCompanion() {
        val items = names(head + "  fun z() { Test.| }\n}")
        assertTrue("Companion" in items, "`Test.` should offer the companion object `Companion`; got $items")
    }

    @Test
    fun classReferenceOffersCompanionMembers() {
        val items = names(head + "  fun z() { Test.| }\n}")
        assertTrue("rainbowColors" in items, "`Test.` should offer the companion member `rainbowColors`; got $items")
    }

    @Test
    fun bareCompanionMemberResolvesToItsType() {
        // `rainbowColors` is a String; `rainbowColors.` inside a member fun must offer String members.
        val items = names(head + "  fun test() { rainbowColors.| }\n}")
        assertTrue("length" in items, "bare companion member should resolve to String → offer `length`; got $items")
    }

    @Test
    fun bareCompanionMemberIsHighlightedAsProperty() {
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val code = head + "  fun test() { rainbowColors.length }\n}"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Test.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val toks = runBlocking { analyzer.semanticHighlighter!!.highlight(doc.file) }
            .map { code.substring(it.range.start, it.range.end) to it.kind.id }
        // The USE inside test() (not the declaration) must be colored as a property.
        assertTrue(
            toks.count { it.first == "rainbowColors" && it.second == "property" } >= 1,
            "the bare companion-member read should be highlighted as a property; got ${toks.filter { it.first == "rainbowColors" }}",
        )
    }
}
