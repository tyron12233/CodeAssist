package dev.ide.playground

import kotlin.test.Test

/** A reported run that showed "(no output)": the program (with a destructuring lambda) must print every line. */
class NoOutputReproTest {
    private val sample = """
        package demo

        data class Point(val x: Int, val y: Int) {
            fun dist2() = x * x + y * y
        }

        fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

        fun main() {
            val points = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
            println("points=${'$'}points")
            println("fib(15)=${'$'}{fib(15)}")

            val sum = points.map { (x, y) -> x + y }
            println(sum)
        }
    """.trimIndent()

    @Test
    fun aProgramWithADestructuringLambdaPrintsEverything() {
        mountHostFiles(VmClasspath.entries)
        val o = PlaygroundEngine(VmClasspath.entries).run(sample)
        println("REPRO error=${o.error} result=${o.result} stdout=[${o.stdout.replace("\n", "|")}] steps=${o.vmSteps}")
        val without = PlaygroundEngine(VmClasspath.entries).run(sample.replace("val sum = points.map { (x, y) -> x + y }\n    println(sum)", "println(1)"))
        println("REPRO without the lambda line: error=${without.error} stdout=[${without.stdout.replace("\n", "|")}]")
        kotlin.test.assertEquals(null, o.error)
        kotlin.test.assertEquals("points=[Point(x=1, y=2), Point(x=3, y=4), Point(x=0, y=5)]\nfib(15)=610\n[3, 7, 5]\n", o.stdout)
        kotlin.test.assertEquals("points=[Point(x=1, y=2), Point(x=3, y=4), Point(x=0, y=5)]\nfib(15)=610\n1\n", without.stdout)
    }

    @Test
    fun theExactScreenshotProgram() {
        mountHostFiles(VmClasspath.entries)
        val src = """
            package demo

            data class Point(val x: Int, val y: Int) {
                fun dist2() = x * x + y * y
            }

            fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

            fun main() {
                val points: List<Point> = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
                println("points=${'$'}points")
                println("farthest=${'$'}{points.maxByOrNull { it: Point -> it.dist2() }}")
                println("fib(15)=${'$'}{fib(15)}")
                println("hello from the IDE's own editor, running in your browser")


                val sum: List<Int> = points.map { (x, y) -> x + y}
                print(sum)
            }
        """.trimIndent()
        val o = PlaygroundEngine(VmClasspath.entries).run(src)
        println("EXACT error=${o.error} result=${o.result} stdout=[${o.stdout.replace("\n", "|")}]")
    }
}
