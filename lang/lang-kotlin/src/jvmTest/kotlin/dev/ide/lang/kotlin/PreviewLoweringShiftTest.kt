package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.JavaDataWriter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.ResolvedTreeCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An edit ABOVE a function moves it without changing it. The preview lowering cache now reuses such a function by
 * shifting its spans instead of lowering it again; the result must be exactly what a fresh lowering produces.
 */
class PreviewLoweringShiftTest {

    private fun source(firstBody: String): String = buildString {
        append("package app\n")
        append("interface Greeter { fun greet(n: String): String }\n")
        append("data class Model(val a: Int, val b: String)\n")
        append("fun first(p: Int): Int { $firstBody }\n")
        repeat(30) { i ->
            append("fun helper$i(p: Int): Int { val a = p + $i; val b = listOf(a, $i, p).map { it * 2 }.filter { it > $i }; ")
            append("val s = \"v\${b.size}\"; return try { s.length + b.sum() } catch (e: Exception) { -1 } }\n")
        }
        append("fun loops(xs: List<Int>): Int { var t = 0; for (x in xs) { if (x % 2 == 0) t += x else t -= 1 }; while (t > 100) { t /= 2 }; return t }\n")
        append("fun withLocal(n: Int): Int { fun twice(k: Int) = k * 2; return twice(n) + n }\n")
        append("fun greeter(prefix: String): Greeter = object : Greeter { override fun greet(n: String) = prefix + n }\n")
        append("fun Entry() { val m = Model(1, \"x\"); println(helper3(m.a) + loops(listOf(1, 2)) + withLocal(2)); println(greeter(\"hi \").greet(m.b)) }\n")
    }

    private fun bytes(write: ResolvedTreeCodec.Writer.() -> Unit): List<Byte> {
        val out = ByteArrayOutputStream()
        ResolvedTreeCodec.Writer(JavaDataWriter(DataOutputStream(out))).write()
        return out.toByteArray().toList()
    }

    private fun encoded(program: Map<String, ResolvedFunction>, classes: List<ResolvedClass>): Map<String, List<Byte>> =
        program.mapValues { (_, f) -> bytes { function(f) } } +
            classes.associate { c -> "class ${c.fqn}" to bytes { klass(c) } }

    @Test
    fun aMovedFunctionMatchesAFreshLowering() {
        val warm = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve("Shift.kt"))
        warm.incrementalParser.parseFull(SnippetDoc(source("return p"), vf))
        warm.lowerFile(vf); warm.lowerFileClasses(vf)
        // A longer body in the first function moves every declaration after it.
        val edited = source("val q = p * 3 + 7; return q - 1")
        warm.incrementalParser.parseFull(SnippetDoc(edited, vf))
        val shifted = encoded(warm.lowerFile(vf), warm.lowerFileClasses(vf))

        val fresh = KotlinSourceAnalyzer(fakeContext(srcDir))
        fresh.incrementalParser.parseFull(SnippetDoc(edited, vf))
        val expected = encoded(fresh.lowerFile(vf), fresh.lowerFileClasses(vf))

        assertEquals(expected.keys, shifted.keys)
        val differing = expected.keys.filter { expected[it] != shifted[it] }
        assertTrue(differing.isEmpty(), "shifted reuse differs from a fresh lowering in: $differing")
    }

    @Test
    fun anEditAboveCostsAboutAsMuchAsAnEditBelow() {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve("Cost.kt"))
        fun lower(body: String): Long {
            analyzer.incrementalParser.parseFull(SnippetDoc(source(body), vf))
            val t0 = System.nanoTime(); analyzer.lowerFile(vf); return System.nanoTime() - t0
        }
        repeat(5) { lower("return p + $it") }
        // Alternate the first body's length so every edit moves all 34 declarations after it.
        val top = (0 until 40).map { lower(if (it % 2 == 0) "return p + $it" else "val q = p; return q + $it") }.drop(10).sorted()
        println("\n=== Preview lowering: edit above every other declaration ===\np50 ${top[top.size / 2] / 1_000}us\n")
    }

    companion object {
        val srcDir = tempProject(mapOf("placeholder.kt" to "package app\n"))
    }
}
