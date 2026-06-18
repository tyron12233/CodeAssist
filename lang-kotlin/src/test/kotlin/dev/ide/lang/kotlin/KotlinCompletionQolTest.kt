package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion quality-of-life: editing an already-named argument replaces only the name (no duplicated `=`),
 * a function whose last parameter is a function type inserts a trailing lambda (`column { }`), the call
 * syntax isn't duplicated when it's already present after the caret, and an expected-type value slot offers
 * the type's own companion constants (`Hue.Clear`).
 */
class KotlinCompletionQolTest {

    private fun items(code: String): List<CompletionItem> =
        runBlocking { analyzer.completeAtCaret(srcDir, "U.kt", code) }.items

    private fun named(code: String, name: String): CompletionItem =
        items(code).first { it.kind == CompletionItemKind.PARAMETER && it.label.substringBefore(' ') == name }

    private fun symbol(code: String, name: String): CompletionItem =
        items(code).first { it.symbol?.name == name }

    // --- A: named-argument name replace ---

    @Test
    fun editingExistingNamedArgInsertsBareName() {
        // Caret on the name of an argument that is ALREADY named (`= 5` present) → insert just `width`, so the
        // editor's whole-token replace yields `box(width = 5)`, not `box(width =  = 5)`.
        assertEquals("width", named("package demo\nfun g() { box(wid|th = 5) }", "width").insertText)
    }

    @Test
    fun freshArgStillInsertsNameEquals() {
        // No `=` yet → the label still brings its own ` = `.
        assertEquals("width = ", named("package demo\nfun g() { box(wid|) }", "width").insertText)
    }

    // --- B: trailing-lambda insert ---

    @Test
    fun soleFunctionTypeParamInsertsTrailingLambda() {
        val it = symbol("package demo\nfun g() { colu|mn }", "column")
        assertEquals("column { }", it.insertText)
    }

    @Test
    fun composableContentParamInsertsTrailingLambda() {
        val it = symbol("package demo\nfun g() { colu|mnC }", "columnC")
        assertEquals("columnC { }", it.insertText)
    }

    @Test
    fun functionTypeLastWithLeadingParamsKeepsParens() {
        // `rows(count, item)` — the leading `count` is required, so the parens come first with the lambda after.
        val it = symbol("package demo\nfun g() { row|s }", "rows")
        assertEquals("rows() { }", it.insertText)
    }

    // --- C: don't duplicate call syntax already present ---

    @Test
    fun existingBracesSuppressTrailingLambda() {
        // `column| { }` already has the lambda — completing it must not add a second `{ }`.
        assertEquals("column", symbol("package demo\nfun g() { colu|mn { } }", "column").insertText)
    }

    @Test
    fun existingParenSuppressesInsertedParens() {
        assertEquals("box", symbol("package demo\nfun g() { bo|x(1) }", "box").insertText)
    }

    // --- D: expected-type companion constants ---

    @Test
    fun expectedTypeOffersCompanionConstants() {
        val items = items("package demo\nfun g() { paint(color = C|) }")
        assertTrue(
            items.any { it.insertText == "Hue.Clear" },
            "a companion constant of the expected type should be offered; got ${items.map { it.insertText }}",
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Widgets.kt" to """
                    package demo
                    fun box(width: Int = 0, height: Int = 0) {}
                    fun column(content: () -> Unit) {}
                    fun columnC(content: @Composable () -> Unit) {}
                    fun rows(count: Int, item: (Int) -> Unit) {}
                    class Hue { companion object { val Clear: Hue = Hue(); val Solid: Hue = Hue() } }
                    fun paint(color: Hue = Hue()) {}
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
