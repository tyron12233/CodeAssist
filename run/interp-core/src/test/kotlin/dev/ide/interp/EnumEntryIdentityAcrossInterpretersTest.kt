package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose preview re-creates its [Interpreter] across recompositions but shares ONE top-level value store, so
 * a top-level `val` (JetNews `post3`, holding `Markup(MarkupType.Link, …)`) keeps enum-entry instances minted by an
 * EARLIER interpreter while a later interpreter's `when (type) { MarkupType.Link -> … }` reads freshly minted
 * entries. Enum entries must compare equal by identity of the ENTRY (class + name), not the instance, or the
 * `when` falls through to Unit ("kotlin.Unit cannot be cast to AnnotatedString$Range" in PostContent).
 */
class EnumEntryIdentityAcrossInterpretersTest {
    @Test
    fun enumEntriesCompareEqualAcrossInterpreterInstancesSharingTheTopLevelStore() {
        val code = """
            enum class MarkupType { Link, Code, Italic, Bold }
            data class Markup(val type: MarkupType, val start: Int)
            val markups = listOf(Markup(MarkupType.Link, 0), Markup(MarkupType.Code, 1))
            fun kind(m: Markup): Int = when (m.type) {
                MarkupType.Italic -> 1
                MarkupType.Link -> 2
                MarkupType.Bold -> 3
                MarkupType.Code -> 4
            }
            fun check(): Int = markups.sumOf { kind(it) }
        """.trimIndent()
        val (functions, classes) = lowerProgramFull(code)
        val store = HashMap<String, Any?>()
        val first = Interpreter(functions, classes = classes, topLevelPropertyStore = store).call(functions.getValue("check/0"), emptyList())
        assertEquals(6, first, "first interpreter (mints markups into the shared store)")
        val second = Interpreter(functions, classes = classes, topLevelPropertyStore = store).call(functions.getValue("check/0"), emptyList())
        assertEquals(6, second, "second interpreter reads the stored markups; its own enum entries must still match them")
    }
}
