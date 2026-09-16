package dev.ide.kotlin.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The outline and the foldable regions: the first analysis here that a host can actually show a user.
 *
 * Both are computed from syntax alone, so they run wherever the parser does. These tests run on the iOS
 * simulator as well as the JVM, which is the point.
 */
class KotlinOutlineTest {

    private val source = """
        package demo

        import kotlin.math.max
        import kotlin.math.min

        /**
         * Explains Holder, over more than one line, because a one-line comment is not worth a fold control
         * and the filter below drops it.
         */
        class Holder(val id: String) : Base() {
            companion object {
                const val DEFAULT = "none"
            }

            enum class Mode { FAST, SLOW }

            var count: Int = 0

            constructor(id: String, count: Int) : this(id) {
                this.count = count
            }

            fun render(prefix: String, upper: Boolean = false): String {
                val body = if (upper) id.uppercase() else id
                return prefix + body
            }
        }

        typealias Handler = (Int) -> Unit

        fun topLevel() {
            println("hi")
        }
    """.trimIndent()

    private fun outline() = KotlinOutline.symbols(source)

    @Test
    fun everyDeclarationIsListedInSourceOrder() {
        val names = outline().map { it.name }
        assertEquals(
            listOf("Holder", "companion", "DEFAULT", "Mode", "FAST", "SLOW", "count", "constructor",
                "render", "Handler", "topLevel"),
            names,
        )
    }

    @Test
    fun kindsMatchThePlatformVocabulary() {
        val byName = outline().associate { it.name to it.kind }
        assertEquals("class", byName["Holder"])
        assertEquals("enum", byName["Mode"])
        assertEquals("enum_constant", byName["FAST"])
        assertEquals("field", byName["count"])
        assertEquals("method", byName["render"])
        assertEquals("constructor", byName["constructor"])
        assertEquals("class", byName["Handler"], "a typealias reads as a type in an outline")
    }

    @Test
    fun nestingIsReportedByDepth() {
        val byName = outline().associate { it.name to it.depth }
        assertEquals(0, byName["Holder"])
        assertEquals(0, byName["topLevel"])
        assertEquals(1, byName["count"], "a member is one level in")
        assertEquals(1, byName["companion"])
        assertEquals(2, byName["DEFAULT"], "and a companion's member is two")
        assertEquals(2, byName["FAST"], "as is an enum entry")
    }

    @Test
    fun aCompanionObjectIsNamedRatherThanBlank() {
        // It has no name of its own, and a blank row in an outline is worse than a conventional one.
        assertTrue(outline().any { it.name == "companion" && it.depth == 1 })
    }

    @Test
    fun theOffsetPointsAtTheNameSoNavigationLandsOnIt() {
        val render = assertNotNull(outline().firstOrNull { it.name == "render" })
        assertEquals(
            "render",
            source.substring(render.nameOffset, render.nameOffset + "render".length),
            "nameOffset must be where the name starts, since that is where the caret goes",
        )
        assertTrue(render.endOffset > render.nameOffset)
        assertTrue(
            source.substring(render.nameOffset, render.endOffset).endsWith("}"),
            "and endOffset must cover the whole declaration, for sticky-header containment",
        )
    }

    @Test
    fun detailCarriesTheSignature() {
        val render = assertNotNull(outline().firstOrNull { it.name == "render" })
        assertEquals("(prefix: String, upper: Boolean = false)", render.detail)
    }

    @Test
    fun brokenCodeStillProducesWhatItCan() {
        // The normal case for an editor. A half-typed declaration must not blank the outline.
        val symbols = KotlinOutline.symbols("class A {\n    fun good() {}\n    fun \n}")
        assertTrue(symbols.any { it.name == "A" }, "the class survives: $symbols")
        assertTrue(symbols.any { it.name == "good" }, "and so does the complete member: $symbols")
    }

    @Test
    fun anEmptyFileHasAnEmptyOutline() {
        assertEquals(emptyList(), KotlinOutline.symbols(""))
    }

    // --- folding -----------------------------------------------------------------------------------------

    @Test
    fun theImportGroupFoldsAndStartsCollapsed() {
        val imports = assertNotNull(KotlinOutline.folds(source).firstOrNull { it.kind == "imports" })
        assertTrue(imports.collapsedByDefault, "an import block is noise until it is wanted")
        assertTrue(source.substring(imports.startOffset, imports.endOffset).startsWith("import"))
    }

    @Test
    fun bodiesAndCommentsFold() {
        val kinds = KotlinOutline.folds(source).map { it.kind }.toSet()
        assertTrue("classBody" in kinds, kinds.toString())
        assertTrue("functionBody" in kinds, kinds.toString())
        assertTrue("comment" in kinds, "the doc comment is foldable: $kinds")
    }

    @Test
    fun singleLineRegionsAreNotOffered() {
        // A fold control on something already one line does nothing, and a gutter full of them is worse than
        // none. `Mode`'s body and `topLevel`'s `println` line are the cases.
        val folds = KotlinOutline.folds(source)
        assertTrue(
            folds.none { !source.substring(it.startOffset, it.endOffset).contains('\n') },
            "no single-line fold may be offered: ${folds.map { source.substring(it.startOffset, it.endOffset) }}",
        )
    }

    @Test
    fun everyFoldIsAWellFormedRange() {
        for (fold in KotlinOutline.folds(source)) {
            assertTrue(fold.startOffset < fold.endOffset, "$fold")
            assertTrue(fold.endOffset <= source.length, "$fold")
            assertTrue(fold.placeholder.isNotEmpty(), "$fold")
        }
    }

    @Test
    fun brokenCodeFoldsWhatItCan() {
        val folds = KotlinOutline.folds("fun f() {\n    val x = \n}")
        assertTrue(folds.any { it.kind == "functionBody" }, "an unterminated statement still has a body: $folds")
    }
}
