package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.dom.TextRange
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accepting an `override` stub from completion must REPLACE the `override fun` the user already typed (not
 * insert a second one beside it), and must indent the stub's body/closing brace to the member's column.
 * Simulates the editor accept (replace the token range with `insertText`, then apply the item's deletion
 * `additionalEdits`, all right-to-left as `EditorSession.applyEdits` does) and checks the resulting buffer.
 */
class KotlinOverrideCompletionTest {

    private fun completeAndClean(code: String): Pair<CompletionResult, String> {
        val caret = code.indexOf('|')
        val clean = code.removeRange(caret, caret + 1)
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) } to clean
    }

    private fun applyAccept(text: String, replace: TextRange, item: CompletionItem): String {
        val edits = (listOf(replace to item.insertText) +
            item.additionalEdits.map { it.range to it.newText }).sortedByDescending { it.first.start }
        var s = text
        for ((r, t) in edits) s = s.substring(0, r.start) + t + s.substring(r.end)
        return s
    }

    @Test
    fun acceptingOverrideReplacesTypedKeywordsAndIndentsBody() {
        val (result, clean) = completeAndClean(
            "package demo\n" +
                "interface Base { fun onCreate(x: Int) }\n" +
                "class Foo : Base {\n" +
                "    override fun onCr|\n" +
                "}"
        )
        val item = result.items.firstOrNull { it.label.startsWith("override fun onCreate") }
            ?: error("no override item for onCreate; got ${result.items.map { it.label }}")
        val applied = applyAccept(clean, result.replacementRange, item)

        // Exactly ONE `override fun onCreate` — the typed `override fun ` was replaced, not duplicated.
        assertEquals(
            1, Regex("override fun onCreate").findAll(applied).count(),
            "the override stub duplicated the typed keywords instead of replacing them:\n$applied",
        )
        assertTrue("override fun override" !in applied && "fun fun" !in applied, "duplicate keyword:\n$applied")
        // Body + closing brace indented to the member's column (4-space class body → 8-space body, 4-space `}`).
        assertTrue(
            "\n        TODO(\"Not yet implemented\")" in applied,
            "the stub body is not indented to the member column:\n$applied",
        )
        assertTrue("\n    }" in applied, "the closing brace is not aligned to the member column:\n$applied")
        // The deletion that removed the typed keywords is carried as an additional edit (the range before the name).
        assertTrue(item.additionalEdits.size == 1, "expected one leading-keyword deletion edit; got ${item.additionalEdits}")
        assertEquals("", item.additionalEdits.single().newText, "the leading-keyword edit must be a deletion")
    }

    @Test
    fun acceptingOverridePreservesALeadingAnnotation() {
        // The deletion starts at the `override`/`fun` keyword, not the declaration start, so a leading
        // annotation the user typed survives (only the `override fun` keywords are replaced).
        val (result, clean) = completeAndClean(
            "package demo\n" +
                "interface Base { fun onCreate(x: Int) }\n" +
                "class Foo : Base {\n" +
                "    @Deprecated(\"x\") override fun onCr|\n" +
                "}"
        )
        val item = result.items.firstOrNull { it.label.startsWith("override fun onCreate") }
            ?: error("no override item for onCreate; got ${result.items.map { it.label }}")
        val applied = applyAccept(clean, result.replacementRange, item)
        assertTrue("@Deprecated(\"x\")" in applied, "a leading annotation must be preserved:\n$applied")
        assertEquals(
            1, Regex("override fun onCreate").findAll(applied).count(),
            "the override stub still duplicated with an annotation present:\n$applied",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
