package dev.ide.core.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anti-hardcoding source check ([missingConstructs]): an exercise passes only when the learner actually
 * used the required constructs, not by printing the expected answer as a literal (or hiding the pattern in a
 * comment / string).
 */
class LearnCheckTest {

    @Test
    fun realSolutionSatisfiesRequirements() {
        val code = """
            fun add(a: Int, b: Int): Int { return a + b }
            fun main() { println(add(2, 3)) }
        """.trimIndent()
        assertTrue(missingConstructs(code, listOf("fun add", "add(2, 3)")).isEmpty())
    }

    @Test
    fun hardcodedOutputIsRejected() {
        // Prints "5" without defining or calling add — both constructs missing.
        val code = "fun main() { println(5) }"
        assertEquals(listOf("fun add", "add(2, 3)"), missingConstructs(code, listOf("fun add", "add(2, 3)")))
    }

    @Test
    fun patternHiddenInAStringDoesNotCount() {
        val code = """fun main() { println("fun add returns add(2, 3)") }"""
        assertEquals(listOf("fun add", "add(2, 3)"), missingConstructs(code, listOf("fun add", "add(2, 3)")))
    }

    @Test
    fun patternInACommentDoesNotCount() {
        val code = "fun main() {\n    // fun add(a, b) then call add(2, 3)\n    println(5)\n}"
        assertEquals(listOf("fun add", "add(2, 3)"), missingConstructs(code, listOf("fun add", "add(2, 3)")))
    }

    @Test
    fun matchingIsWhitespaceInsensitive() {
        val code = "fun  add ( a : Int , b : Int ) : Int { return a + b }\nfun main(){ println( add( 2 ,3 ) ) }"
        assertTrue(missingConstructs(code, listOf("fun add", "add(2, 3)")).isEmpty())
    }

    @Test
    fun stripKeepsQuotesDropsContents() {
        assertEquals("""println("")""", stripCommentsAndStrings("""println("hello")""").trim())
    }
}
