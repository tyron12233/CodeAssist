package dev.ide.lang.kotlin

import dev.ide.lang.resolve.StructureItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file structure (breadcrumbs, outline) is asked for while typing, usually before analysis has parsed the
 * new text, so it starts from the analyzer's previous parse. Whatever the edit, the result must be exactly the
 * structure a fresh parse of the new text gives.
 */
class KotlinFileStructureReparseTest {

    private val base = """
        package demo

        class Store(private val seed: Int) {
            private val items = mutableListOf<String>()

            fun add(name: String): String {
                items += name
                return name
            }

            fun total(): Int {
                var sum = 0
                for (item in items) sum += item.length
                return sum
            }
        }

        fun topLevel(x: Int): Int = x * 2

        object Registry {
            val names = listOf("a", "b")
        }
    """.trimIndent() + "\n"

    /** [edited]'s structure from an analyzer whose last parse and structure are of [base], and from a fresh
     *  parse. */
    private fun structures(edited: String): Pair<List<StructureItem>, List<StructureItem>> {
        val srcDir = tempProject(mapOf("Store.kt" to base))
        val vf = DiskFile(srcDir.resolve("Store.kt"))
        val warm = KotlinSourceAnalyzer(fakeContext(srcDir))
        runBlocking { warm.incrementalParser.parseFull(SnippetDoc(base, vf)) }
        // Asked for the base text first, as the breadcrumb is before the keystroke: the edited answer then also
        // builds on the previous answer, carrying over the declarations the edit left alone.
        warm.fileStructure(vf, base)
        val fromPrevious = warm.fileStructure(vf, edited)
        val fresh = KotlinSourceAnalyzer(fakeContext(srcDir)).fileStructure(vf, edited)
        return fromPrevious to fresh
    }

    private fun assertSameStructure(edited: String) {
        assertTrue(edited != base, "the edit must change the text")
        val (fromPrevious, fresh) = structures(edited)
        assertTrue(fresh.isNotEmpty())
        assertEquals(fresh, fromPrevious)
    }

    @Test
    fun keystrokeInsideABodyShiftsLaterOffsets() {
        assertSameStructure(base.replace("var sum = 0", "var sum = 0\n        val extra = items.size"))
    }

    @Test
    fun declarationAddedAtTopLevel() {
        assertSameStructure(base.replace("fun topLevel", "fun inserted(): Unit {}\n\nfun topLevel"))
    }

    @Test
    fun unbalancedBraceInsideABody() {
        assertSameStructure(base.replace("return name", "if (name.isEmpty()) {\n        return name"))
    }

    @Test
    fun anEditBeforeLaterDeclarationsMovesTheirItems() {
        assertSameStructure(base.replace("package demo", "package demo.extra"))
    }

    @Test
    fun memberRenamed() {
        assertSameStructure(base.replace("fun total()", "fun grandTotal()"))
    }
}
