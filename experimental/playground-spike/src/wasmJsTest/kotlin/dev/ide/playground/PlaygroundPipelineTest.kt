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

    /** How many VM steps one interpreted loop iteration / call costs: the multiplier a compiler would remove. */
    @Test
    fun whatInterpretingCostsInVmSteps() {
        mountHostFiles(VmClasspath.entries)
        val engine = PlaygroundEngine(VmClasspath.entries, budgetMillis = 120_000)
        fun steps(label: String, body: String, iterations: Int): Long {
            val o = engine.run("package demo\nfun main() {\n$body\n}\n")
            check(o.error == null) { "$label: ${o.error}" }
            println("COST $label: ${o.vmSteps} steps, ${o.runMillis} ms for $iterations iterations")
            return o.vmSteps
        }
        val base = steps("empty", "", 1)
        val a = steps("loop 2000", "var s = 0L\nfor (i in 1..2000) { s += i }\nprintln(s)", 2000)
        val b = steps("loop 4000", "var s = 0L\nfor (i in 1..4000) { s += i }\nprintln(s)", 4000)
        println("COST per iteration of `s += i`: ${(b - a) / 2000} VM steps")
        val c = steps("while 2000", "var i = 0\nvar acc = 1\nwhile (i < 2000) { acc = acc * 3 % 1000003 + i; i++ }\nprintln(acc)", 2000)
        val d = steps("while 4000", "var i = 0\nvar acc = 1\nwhile (i < 4000) { acc = acc * 3 % 1000003 + i; i++ }\nprintln(acc)", 4000)
        println("COST per iteration of `acc = acc * 3 % 1000003 + i; i++` + `i < n`: ${(d - c) / 2000} VM steps")
        println("COST startup (empty main): $base steps")
    }

    @Test
    fun whereTheStepsGo() {
        mountHostFiles(VmClasspath.entries)
        val engine = PlaygroundEngine(VmClasspath.entries, budgetMillis = 120_000)
        val o = engine.run("package demo\nfun main() {\nvar s = 0L\nfor (i in 1..4000) { s += i }\nprintln(s)\n}\n", profile = true)
        check(o.error == null) { "${o.error}" }
        val total = o.vmSteps.toDouble()
        println("HOT total ${o.vmSteps} steps")
        for ((name, steps) in o.hot) println("HOT ${(steps * 1000 / total).toInt() / 10.0}% ${steps} $name")
    }
}
