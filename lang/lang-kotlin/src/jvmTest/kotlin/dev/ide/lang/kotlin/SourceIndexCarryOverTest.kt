package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.lang.dom.DomNode
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.SourceFile
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.kotlin.symbols.sameDeclarations
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rebuild of the focal file carries over every top-level declaration the edit left alone. What it carries
 * must be exactly what reading the new text from scratch gives: the same declarations, and nodes in the NEW
 * tree at the same places, not the old tree's.
 */
class SourceIndexCarryOverTest {

    private val dir = Files.createTempDirectory("carry-over")
    private val vf = DiskFile(dir.resolve("Main.kt"))

    @AfterTest
    fun forget() {
        SourceIndexBuilder.forgetLastBuilds()
    }

    private val base = """
        package p

        import kotlin.math.max

        typealias Names = List<String>

        data class Point(val x: Int, val y: Int = 0) {
            fun plus(o: Point): Point {
                return Point(x + o.x, max(y, o.y))
            }
        }

        enum class Color { RED, GREEN }

        interface Shape {
            val area: Double
            fun describe(): String = "shape"
        }

        class Box<T : Any>(private val value: T) : Shape {
            override val area: Double get() = 1.0
            val doubled = listOf(value, value).map { it.toString() }

            fun unwrap(): T {
                val v = value
                return v
            }

            class Nested(val n: Int)

            companion object {
                @JvmStatic fun <T : Any> of(v: T) = Box(v)
            }
        }

        fun String.shout(): String {
            return uppercase()
        }

        val Point.length get() = x + y

        fun total(points: List<Point>) = points.sumOf { it.x }

        object Registry {
            val names: Names = listOf("a")
        }
    """.trimIndent() + "\n"

    private fun build(kt: KtFile): SourceFile =
        SourceIndexBuilder.extractFrom(kt, KotlinParsedFile(kt, vf, 0), vf.path)

    /** [edited] built after [base], from [base]'s tree reparsed; and the same text built with nothing remembered. */
    private fun carriedAndFresh(edited: String): Triple<SourceFile, SourceFile, Int> {
        val before = KotlinParserHost.parse(vf.name, base)
        build(before)
        val after = KotlinParserHost.reparse(before, vf.name, edited)
        val carried = build(after)
        val count = SourceIndexBuilder.lastCarriedCount
        SourceIndexBuilder.forgetLastBuilds()
        val fresh = build(KotlinParserHost.parse(vf.name, edited))
        assertTrue(nodes(carried).all { owner(it) === after }, "every carried node must be in the new tree")
        return Triple(carried, fresh, count)
    }

    private fun owner(node: DomNode): KtFile = (node as KotlinDomNode).psi.containingKtFile

    private fun nodes(f: SourceFile): List<DomNode> = buildList {
        (f.topLevel + f.extensions).forEach { it.node?.let(::add) }
        f.classes.forEach { c ->
            c.node?.let(::add)
            (c.members + c.constructors).forEach { m -> m.node?.let(::add) }
        }
    }

    private fun assertSameAsFresh(edited: String, expectCarried: Int) {
        assertTrue(edited != base, "the edit must change the text")
        val (carried, fresh, count) = carriedAndFresh(edited)
        assertTrue(sameDeclarations(carried, fresh), "carried declarations must equal a fresh build")
        assertEquals(nodes(fresh).map { it.range }, nodes(carried).map { it.range }, "node positions")
        assertEquals(nodes(fresh).map { it.kind }, nodes(carried).map { it.kind }, "node kinds")
        assertEquals(expectCarried, count, "declarations carried over")
    }

    private val topLevelCount = 9

    @Test
    fun aKeystrokeInAnEarlyBodyCarriesEveryOtherDeclarationShiftedByTheEdit() {
        assertSameAsFresh(base.replace("return Point(x + o.x", "return Point(x + o.x + 1"), topLevelCount - 1)
    }

    @Test
    fun aKeystrokeInALateBodyCarriesEverythingBeforeIt() {
        assertSameAsFresh(base.replace("return uppercase()", "return uppercase().trim()"), topLevelCount - 1)
    }

    @Test
    fun anElvisInsideABodyAddsANodeAndStillCarriesTheRest() {
        // `?:` and `!!` build nodes inside a collapsed body, so every later node's index moves.
        assertSameAsFresh(base.replace("val v = value", "val v = value ?: value!!"), topLevelCount - 1)
    }

    @Test
    fun anEditInsideALambdaInAnInitializerRebuildsThatDeclaration() {
        assertSameAsFresh(base.replace("map { it.toString() }", "map { it.toString() + \"!\" }"), topLevelCount - 1)
    }

    @Test
    fun anAddedDeclarationRebuildsEverything() {
        assertSameAsFresh(base.replace("enum class Color", "fun added() = 1\n\nenum class Color"), 0)
    }

    @Test
    fun aRenamedClassIsReadAgain() {
        assertSameAsFresh(base.replace("enum class Color", "enum class Colour"), topLevelCount - 1)
    }

    @Test
    fun anImportChangeRebuildsEverything() {
        assertSameAsFresh(base.replace("import kotlin.math.max", "import kotlin.math.max\nimport kotlin.math.min"), 0)
    }

    @Test
    fun theSameTextFromANewParseCarriesEverything() {
        val first = KotlinParserHost.parse(vf.name, base)
        build(first)
        val second = KotlinParserHost.parse(vf.name, base)
        val carried = build(second)
        assertEquals(topLevelCount, SourceIndexBuilder.lastCarriedCount)
        assertTrue(nodes(carried).all { owner(it) === second })
    }

    @Test
    fun theCompletionMarkerInABodyCarriesTheRest() {
        // Completion builds from the buffer with a marker spliced in at the caret, the analyzer from the buffer
        // itself; both pass through the same remembered build.
        val marker = "IntellijIdeaRulezzz"
        val plain = KotlinParserHost.parse(vf.name, base)
        build(plain)
        val spliced = KotlinParserHost.parse(vf.name, base.replace("val v = value", "val v = value$marker"))
        build(spliced)
        assertEquals(topLevelCount - 1, SourceIndexBuilder.lastCarriedCount)
    }

    @Test
    fun theCountOfTopLevelDeclarationsIsWhatTheTestsAssume() {
        assertEquals(topLevelCount, KotlinParserHost.parse(vf.name, base).declarations.size)
    }
}
