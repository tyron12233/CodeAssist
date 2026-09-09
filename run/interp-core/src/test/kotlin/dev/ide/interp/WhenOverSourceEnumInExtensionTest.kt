package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JetNews `Markup.toAnnotatedStringItem`: `when (this.type) { MarkupType.Italic -> { … } … }` over a SOURCE enum
 * read through the extension receiver, each branch a block. On device the `when` produced `kotlin.Unit`
 * ("Unit cannot be cast to AnnotatedString.Range"), i.e. no branch matched.
 */
class WhenOverSourceEnumInExtensionTest {

    @Test
    fun whenOverAnEnumPropertyOfTheExtensionReceiverSelectsTheBranch() {
        val code = """
            enum class MarkupType { Link, Code, Italic, Bold }
            data class Markup(val type: MarkupType, val start: Int, val end: Int)
            fun Markup.item(): Int {
                return when (this.type) {
                    MarkupType.Italic -> { start + 10 }
                    MarkupType.Link -> { start + 20 }
                    MarkupType.Bold -> { start + 30 }
                    MarkupType.Code -> { start + 40 }
                }
            }
            fun use(): Int = listOf(Markup(MarkupType.Link, 1, 5), Markup(MarkupType.Code, 2, 6)).map { it.item() }.sum()
        """.trimIndent()
        assertEquals(21 + 42, runProgram(code, "use/0", emptyList()))
    }
}
