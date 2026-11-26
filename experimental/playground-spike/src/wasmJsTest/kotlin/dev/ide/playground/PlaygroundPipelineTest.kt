package dev.ide.playground

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Playground, end to end, with no JVM anywhere at run time: source text in, program output out.
 *
 *   1. parse + lower, natively (wasm), against the real kotlin-stdlib read from the file system;
 *   2. encode the lowered program as the wire blob;
 *   3. hand it to `:interp-core`, interpreted by `:jvm-vm` from its jars.
 */
class PlaygroundPipelineTest {

    private fun run(source: String): Pair<String, String> {
        mountHostFiles(VmClasspath.entries)
        val outcome = PlaygroundEngine(VmClasspath.entries).run(source)
        outcome.error?.let { error("the program failed: $it\nstdout so far: ${outcome.stdout}") }
        return outcome.stdout to outcome.result!!
    }

    @Test
    fun helloWorldPrintsFromSource() {
        val (stdout, result) = run(
            """
            package demo

            fun main() {
                println("Hello, Playground!")
            }
            """.trimIndent(),
        )
        assertEquals("Hello, Playground!\n", stdout)
        assertEquals("kotlin.Unit", result)
    }

    @Test
    fun aRealisticProgramRuns() {
        val (stdout, _) = run(
            """
            package demo

            data class Point(val x: Int, val y: Int) {
                fun dist2(): Int = x * x + y * y
            }

            enum class Shape { CIRCLE, SQUARE }

            fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

            fun describe(s: Shape): String = when (s) {
                Shape.CIRCLE -> "round"
                Shape.SQUARE -> "boxy"
            }

            fun main() {
                val points = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
                println("points=${'$'}points")
                println("far=${'$'}{points.maxByOrNull { it.dist2() }}")
                println("sum=${'$'}{points.sumOf { it.x + it.y }}")
                val byX = points.groupBy { it.x % 2 }
                println("byX=${'$'}byX")
                println("fib(15)=${'$'}{fib(15)}")
                println(Shape.values().joinToString { describe(it) })
                val words = "the quick brown fox".split(" ").map { it.replaceFirstChar { c -> c.uppercase() } }
                println(words.joinToString("-"))
            }
            """.trimIndent(),
        )
        assertEquals(
            """
            points=[Point(x=1, y=2), Point(x=3, y=4), Point(x=0, y=5)]
            far=Point(x=3, y=4)
            sum=15
            byX={1=[Point(x=1, y=2), Point(x=3, y=4)], 0=[Point(x=0, y=5)]}
            fib(15)=610
            round, boxy
            The-Quick-Brown-Fox
            """.trimIndent() + "\n",
            stdout,
        )
    }
}
