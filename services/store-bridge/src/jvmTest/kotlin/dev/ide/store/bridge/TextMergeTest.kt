package dev.ide.store.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMergeTest {

    private val base = """
        fun main() {
            greet("world")
        }

        fun greet(name: String) {
            println("Hello, ${'$'}name")
        }
    """.trimIndent() + "\n"

    @Test
    fun editsInDifferentPlacesBothSurvive() {
        // The user renamed the greeting; the publisher added a line to main.
        val ours = base.replace("Hello, ", "Hi, ")
        val theirs = base.replace("    greet(\"world\")\n", "    greet(\"world\")\n    greet(\"store\")\n")
        val merged = TextMerge.merge(base, ours, theirs)
        assertEquals(0, merged.conflicts)
        assertEquals(
            base.replace("Hello, ", "Hi, ").replace("    greet(\"world\")\n", "    greet(\"world\")\n    greet(\"store\")\n"),
            merged.text,
        )
    }

    @Test
    fun theSameLineChangedTwoWaysIsAConflictWithBothSidesKept() {
        val ours = base.replace("Hello, ", "Hi, ")
        val theirs = base.replace("Hello, ", "Welcome, ")
        val merged = TextMerge.merge(base, ours, theirs, theirsLabel = "New version (1.1.0)")
        assertEquals(1, merged.conflicts)
        assertTrue(merged.text.contains("<<<<<<< Your version\n    println(\"Hi, \$name\")\n=======\n    println(\"Welcome, \$name\")\n>>>>>>> New version (1.1.0)\n"), merged.text)
        // Everything around the conflict is untouched.
        assertTrue(merged.text.startsWith("fun main() {\n    greet(\"world\")\n}\n"))
    }

    @Test
    fun onlyOneSideChangedTakesThatSide() {
        val theirs = base.replace("world", "everyone")
        assertEquals(theirs, TextMerge.merge(base, base, theirs).text)
        assertEquals(theirs, TextMerge.merge(base, theirs, base).text)
    }

    @Test
    fun bothMakingTheSameChangeIsNotAConflict() {
        val same = base.replace("world", "everyone")
        assertEquals(TextMerge.Result(same, 0), TextMerge.merge(base, same, same))
    }

    @Test
    fun linesAddedAtTheEndByBothSidesConflictOnlyIfTheyDiffer() {
        val ours = base + "// mine\n"
        val theirs = base + "// theirs\n"
        val merged = TextMerge.merge(base, ours, theirs)
        assertEquals(1, merged.conflicts)
        assertTrue(merged.text.endsWith("<<<<<<< Your version\n// mine\n=======\n// theirs\n>>>>>>> New version\n"))
    }

    @Test
    fun aFileBothSidesCreatedMergesAgainstAnEmptyBase() {
        val merged = TextMerge.merge("", "a\nb\n", "a\nc\n")
        assertEquals(1, merged.conflicts)
        assertTrue(merged.text.startsWith("a\n<<<<<<< Your version\nb\n=======\nc\n>>>>>>> New version\n"), merged.text)
    }

    @Test
    fun crlfFilesMergeToo() {
        val b = "one\r\ntwo\r\nthree\r\n"
        val merged = TextMerge.merge(b, "ONE\r\ntwo\r\nthree\r\n", "one\r\ntwo\r\nTHREE\r\n")
        assertEquals(TextMerge.Result("ONE\r\ntwo\r\nTHREE\r\n", 0), merged)
    }
}
