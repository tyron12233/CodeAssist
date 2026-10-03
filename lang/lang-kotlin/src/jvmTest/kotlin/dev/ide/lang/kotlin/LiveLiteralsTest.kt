package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.LiveLiteralState
import dev.ide.lang.kotlin.interp.LiveLiterals
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.liveEditDirtyClosure
import dev.ide.lang.kotlin.interp.walk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Live literals: an edit confined to one literal is patched into the lowered program (no re-lowering), the
 * edited function becomes a new instance and every other function keeps its instance; anything that isn't
 * provably a same-typed literal edit is refused so the caller re-lowers.
 */
class LiveLiteralsTest {

    private val header = """
        package demo
        annotation class Preview
        annotation class Composable
    """.trimIndent() + "\n"

    private fun lowered(body: String): LiveLiteralState {
        val code = header + body
        val srcDir = tempProject(mapOf("Ui.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve("Ui.kt"))
        runBlocking { analyzer.incrementalParser.parseFull(SnippetDoc(code, vf)) }
        val program = analyzer.lowerFile(vf)
        program.values.forEach { assertTrue(it.isComplete, "${it.name} should lower cleanly: ${it.diagnostics}") }
        return LiveLiteralState(code, program, program.keys)
    }

    private fun LiveLiteralState.edit(from: String, to: String): LiveLiteralState? {
        assertEquals(1, Regex(Regex.escape(from)).findAll(text).count(), "`$from` must occur once")
        return LiveLiterals.patch(this, text.replace(from, to))
    }

    private fun consts(fn: ResolvedFunction): List<Any?> {
        val out = ArrayList<Any?>()
        fn.params.forEach { p -> p.default?.walk { if (it is RNode.Const) out += it.value } }
        fn.body.walk { if (it is RNode.Const) out += it.value }
        return out
    }

    @Test
    fun intLiteralEditPatchesOnlyTheEditedFunction() {
        val base = lowered(
            """
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun CardPreview() { Card(1) }
            """.trimIndent()
        )
        val next = assertNotNull(base.edit("Card(1)", "Card(42)"))
        assertEquals(listOf<Any?>(42), consts(next.program.getValue("CardPreview/0")))
        assertTrue(next.program["CardPreview/0"] !== base.program["CardPreview/0"], "the edited function is a new instance")
        assertSame(base.program["Card/1"], next.program["Card/1"], "an untouched function keeps its instance")
    }

    @Test
    fun aChangeOfLiteralTypeIsRefused() {
        val base = lowered(
            """
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun CardPreview() { Card(1) }
            """.trimIndent()
        )
        assertNull(base.edit("Card(1)", "Card(1.5)"), "Int → Double changes resolution, so it must re-lower")
        assertNull(base.edit("Card(1)", "Card(3000000000)"), "an Int literal widening to Long must re-lower")
        assertNull(base.edit("Card(1)", "Card(1x)"), "not a literal")
    }

    @Test
    fun aNonLiteralEditIsRefused() {
        val base = lowered(
            """
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun CardPreview() { Card(1) }
            """.trimIndent()
        )
        assertNull(base.edit("CardPreview()", "CardPreviewX()"))
        assertNull(LiveLiterals.patch(base, base.text), "no change is not an edit")
    }

    @Test
    fun stringLiteralEdits() {
        val base = lowered(
            """
            @Composable fun Label(s: String) {}
            @Preview @Composable fun P() { Label("Hello") }
            """.trimIndent()
        )
        val next = assertNotNull(base.edit("\"Hello\"", "\"Hello, world\""))
        assertEquals(listOf<Any?>("Hello, world"), consts(next.program.getValue("P/0")))
        assertNull(base.edit("\"Hello\"", "\"Hel\"lo\""), "a quote ends the string")
        assertNull(base.edit("\"Hello\"", "\"Hel\\nlo\""), "an escape changes the template's structure")
        assertNull(base.edit("\"Hello\"", "\"Hel\$lo\""), "a template changes the template's structure")
    }

    @Test
    fun templateFragmentEdits() {
        val base = lowered(
            """
            @Composable fun Label(s: String) {}
            @Preview @Composable fun P() { val name = "x"; Label("Hi ${'$'}name!") }
            """.trimIndent()
        )
        val next = assertNotNull(base.edit("Hi ", "Hey "), "the leading fragment is a literal")
        assertTrue("Hey " in consts(next.program.getValue("P/0")))
        val bang = assertNotNull(base.edit("name!", "name!!"), "the trailing fragment is a literal")
        assertTrue("!!" in consts(bang.program.getValue("P/0")))
        assertNull(base.edit("name!", "namex!"), "a letter right after `\$name` extends the NAME, not the fragment")
    }

    @Test
    fun booleanCharAndHexLiterals() {
        val base = lowered(
            """
            @Composable fun W(b: Boolean, c: Char, l: Long) {}
            @Preview @Composable fun P() { W(true, 'a', 0xFF6650A4) }
            """.trimIndent()
        )
        assertEquals(listOf<Any?>(false, 'a', 0xFF6650A4), consts(assertNotNull(base.edit("true", "false")).program.getValue("P/0")))
        assertEquals(listOf<Any?>(true, 'z', 0xFF6650A4), consts(assertNotNull(base.edit("'a'", "'z'")).program.getValue("P/0")))
        assertEquals(listOf<Any?>(true, 'a', 0xFFD0BCFF), consts(assertNotNull(base.edit("0xFF6650A4", "0xFFD0BCFF")).program.getValue("P/0")))
    }

    @Test
    fun successiveEditsTrackShiftedSpans() {
        // The first edit grows a literal by two characters; the second must still find the literal AFTER it.
        val base = lowered(
            """
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun P() { Card(1); Card(2) }
            """.trimIndent()
        )
        val first = assertNotNull(base.edit("Card(1)", "Card(100)"))
        val second = assertNotNull(first.edit("Card(2)", "Card(3)"))
        assertEquals(listOf<Any?>(100, 3), consts(second.program.getValue("P/0")))
        // And the first literal is still editable at its new length.
        val third = assertNotNull(second.edit("Card(100)", "Card(7)"))
        assertEquals(listOf<Any?>(7, 3), consts(third.program.getValue("P/0")))
    }

    @Test
    fun topLevelValAndDefaultArgumentLiterals() {
        val base = lowered(
            """
            val Pad = 16
            @Composable fun Card(n: Int = 5) {}
            @Preview @Composable fun P() { Card(Pad) }
            """.trimIndent()
        )
        val pad = assertNotNull(base.edit("Pad = 16", "Pad = 24"))
        assertEquals(listOf<Any?>(24), consts(pad.program.getValue("Pad/0")))
        val def = assertNotNull(base.edit("n: Int = 5", "n: Int = 9"))
        assertEquals(listOf<Any?>(9), consts(def.program.getValue("Card/1")))
    }

    @Test
    fun onlyEditableFunctionsArePatched() {
        val base = lowered(
            """
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun P() { Card(1) }
            """.trimIndent()
        )
        val foreign = LiveLiteralState(base.text, base.program, base.program.keys - "P/0")
        assertNull(foreign.edit("Card(1)", "Card(2)"), "a function from another file must not be patched")
    }

    @Test
    fun dirtyClosureForcesTheCallersOfAChangedFunction() {
        val base = lowered(
            """
            val Pad = 16
            @Composable fun Leaf() { Pad }
            @Composable fun Middle() { Leaf() }
            @Composable fun Other() {}
            @Preview @Composable fun P() { Middle(); Other() }
            """.trimIndent()
        )
        assertEquals(
            setOf("Pad/0", "Leaf/0", "Middle/0", "P/0"),
            liveEditDirtyClosure(base.program, emptyList(), setOf("Pad/0")),
        )
        assertEquals(emptySet(), liveEditDirtyClosure(base.program, emptyList(), emptySet()))
    }
}
