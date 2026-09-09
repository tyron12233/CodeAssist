package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `enum class` used in a `when` expression body. Three regressions, all rooted in enum-constant handling:
 *   1. the function's inferred return type — a `when` over enum constants + `null` is `Enum?`, not unknown;
 *   2. `else` offered as a `when`-entry label;
 *   3. `Enum.` completion lists the constants.
 * The model-level cause of (1)/(3) was that enum ENTRIES (a `KtEnumEntry` IS a `KtClassOrObject`) were
 * registered as nested classes, so `Test.A` mis-resolved to a classifier `A` rather than the enum value.
 */
class KotlinEnumWhenTest {

    private fun hints(code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.inlayHints.hints(doc.file, TextRange(0, code.length)) }
            .map { it.parts.joinToString("") { p -> p.text } }
    }

    @Test
    fun issue1_whenOverEnumConstantsInfersNullableEnum() {
        val src = """
            |enum class Test { A, B }
            |fun test(name: String) = when(name) {
            |  "A" -> Test.A
            |  "B" -> Test.B
            |  else -> null
            |}
            |fun main() {
            |  val a = ""
            |  val result = test(a)
            |}
        """.trimMargin()
        assertTrue(": Test?" in hints(src), "result must be inferred Test?; got ${hints(src)}")
    }

    @Test
    fun issue1b_enumConstantAccessIsTheEnumType() {
        assertEquals(listOf(": Test"), hints("enum class Test { A, B }\nfun f() { val z = Test.A }"),
            "Test.A is a value of type Test, not a classifier A")
    }

    @Test
    fun issue2_elseOfferedAsWhenEntryLabel() {
        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(n: String) = when(n) {\n  \"A\" -> 1\n  el| \n}")
        }
        assertTrue(r.items.any { it.label == "else" }, "else must be offered as a when label; got ${r.items.map { it.label }}")
    }

    @Test
    fun issue2b_elseNotOfferedInEntryResultExpression() {
        // After `->` we're in an expression, not a label — `else` must NOT appear there.
        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(n: String) = when(n) {\n  \"A\" -> el| \n}")
        }
        assertTrue(r.items.none { it.label == "else" }, "else must not be offered in the result expression; got ${r.items.map { it.label }}")
    }

    @Test
    fun issue3_enumDotCompletionListsConstants() {
        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "enum class Test { A, B }\nfun f() { val x = Test.| }")
        }
        val names = r.items.mapNotNull { it.symbol?.name }
        assertTrue("A" in names && "B" in names, "Test. must list A and B; got $names")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
    }
}
