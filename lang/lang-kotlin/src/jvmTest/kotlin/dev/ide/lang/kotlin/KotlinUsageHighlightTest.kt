package dev.ide.lang.kotlin

import dev.ide.lang.highlight.UsageAccess
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Highlight-usages-at-caret ([KotlinEditorFeatures.usageHighlightsAt]): the names in the open file that ARE the
 * symbol under the caret, each a read, a write or the declaration. A same-named symbol elsewhere in the file is
 * not one, which is what it adds over the editor's textual whole-word scan.
 */
class KotlinUsageHighlightTest {

    /** The highlights for the caret on the [nth] [needle] in Main.kt, as `line:ACCESS` (1-based lines). */
    private fun highlights(needle: String, nth: Int = 0): List<String>? {
        val t = Files.readString(srcDir.resolve("Main.kt"))
        var at = -1
        repeat(nth + 1) { at = t.indexOf(needle, at + 1) }
        check(at >= 0) { "'$needle' not in Main.kt" }
        return analyzer.usageHighlightsAt(DiskFile(srcDir.resolve("Main.kt")), t, at, ::readSource)
            ?.map { "${t.substring(0, it.range.start).count { c -> c == '\n' } + 1}:${it.access}" }
    }

    @Test
    fun aLocalsUsesInItsOwnFunctionOnlyWithTheAssignmentAsAWrite() {
        assertEquals(
            listOf("14:${UsageAccess.DECLARATION}", "15:${UsageAccess.WRITE}", "15:${UsageAccess.READ}", "16:${UsageAccess.READ}"),
            highlights("x = x"),
            "two()'s own `x` (lines 20-21) is a different symbol",
        )
    }

    @Test
    fun aPropertysCompoundAssignmentsIncrementsAndQualifiedAssignmentsAreWrites() {
        val found = assertNotNull(highlights("count", nth = 2)) // the `count++` line
        assertEquals(
            listOf("4:DECLARATION", "6:WRITE", "7:WRITE", "8:READ", "9:WRITE"),
            found,
        )
    }

    @Test
    fun theSameAnswerFromTheDeclaration() {
        assertEquals(highlights("x = x"), highlights("x = 1"))
    }

    @Test
    fun nothingAtWhitespace() {
        assertNull(highlights("\n\n"))
    }

    companion object {
        private val FILES = mapOf(
            "Main.kt" to """
                package demo

                class Counter {
                    var count = 0
                    fun bump() {
                        count += 1
                        count++
                        println(count)
                        this.count = 5
                    }
                }

                fun one() {
                    var x = 1
                    x = x + 2
                    println(x)
                }

                fun two() {
                    val x = "other"
                    println(x)
                }
            """.trimIndent() + "\n",
        )

        val srcDir: Path = tempProject(FILES)
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))

        fun readSource(path: String): CharSequence? = runCatching { Files.readString(Paths.get(path)) }.getOrNull()
    }
}
