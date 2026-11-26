package dev.ide.playground

import dev.ide.platform.writeFileAtomically
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A project of several files: the editor sees across them, and a run follows the program from `main` into the others. */
class MultiFileTest {
    private val main = "/project/Main.kt"
    private val shapes = "/project/shapes/Shapes.kt"
    private val util = "/project/Util.kt"

    private val shapesSrc = """
        package shapes

        data class Rect(val w: Int, val h: Int) {
            fun area() = w * h
        }

        fun describe(r: Rect): String = "rect ${'$'}{r.w}x${'$'}{r.h} area=${'$'}{r.area()}"
    """.trimIndent()

    private val utilSrc = """
        package demo

        const val GREETING = "hello"

        fun twice(n: Int): Int = n * 2
    """.trimIndent()

    private val mainSrc = """
        package demo

        import shapes.Rect
        import shapes.describe

        fun main() {
            val r = Rect(3, 4)
            println(describe(r))
            println(GREETING + " " + twice(21))
        }
    """.trimIndent()

    private fun session(): EditorSession {
        mountHostFiles(VmClasspath.entries)
        val libs = VmClasspath.entries.filter {
            val n = it.substringAfterLast('/')
            n.startsWith("kotlin-stdlib-") || n.startsWith("kotlinx-coroutines-core-jvm-")
        }
        return EditorSession(libs).also {
            it.put(shapes, shapesSrc); it.put(util, utilSrc); it.put(main, mainSrc)
        }
    }

    @Test
    fun aRunFollowsMainIntoTheOtherFiles() {
        mountHostFiles(VmClasspath.entries)
        for ((path, text) in listOf(shapes to shapesSrc, util to utilSrc, main to mainSrc)) {
            check(writeFileAtomically(path, text.encodeToByteArray()))
        }
        val outcome = PlaygroundEngine(VmClasspath.entries).run(main, mainSrc)
        println("MULTI run: error=${outcome.error} stdout=${outcome.stdout.trim().replace("\n", " | ")}")
        assertEquals(null, outcome.error)
        assertEquals("rect 3x4 area=12\nhello 42\n", outcome.stdout)
    }

    @Test
    fun theEditorSeesAcrossFiles() = runTest {
        val s = session()

        // A name declared in another file resolves: no unresolved reference on the imports and calls in Main.kt.
        val problems = s.diagnose(main, mainSrc).filter { it.severity == "ERROR" }
        println("MULTI diagnostics on Main.kt: ${problems.map { it.message }}")
        assertTrue(problems.isEmpty(), "Main.kt resolves Rect, describe, GREETING and twice from other files; got $problems")

        // Members of a class declared in another file complete.
        val partial = mainSrc.replace("println(describe(r))", "println(r.)")
        val offset = partial.indexOf("r.)") + 2
        val labels = s.complete(main, partial, offset).items.map { it.label.substringBefore('(') }
        println("MULTI completion after `r.`: ${labels.take(8)}")
        assertTrue("area" in labels && "w" in labels, "Rect's members complete across files; got ${labels.take(20)}")

        // Go to declaration lands in the other file.
        val def = s.call("definition", main, mainSrc, mainSrc.indexOf("describe(r)") + 2, 0)
        println("MULTI definition of describe: $def")
        assertTrue(def.contains(shapes), "describe is declared in Shapes.kt; got $def")

        // An edit in one file is seen from another without anything being saved.
        s.put(util, utilSrc.replace("fun twice(n: Int): Int = n * 2", "fun twice(n: Int): Int = n * 2\nfun thrice(n: Int): Int = n * 3"))
        val withThrice = mainSrc.replace("twice(21)", "thr")
        val names = s.complete(main, withThrice, withThrice.indexOf("thr") + 3).items.map { it.label.substringBefore('(') }
        println("MULTI completion of `thr`: ${names.take(5)}")
        assertTrue("thrice" in names, "a function added to Util.kt completes in Main.kt; got ${names.take(20)}")

        // Removing a file makes what it declared unresolved.
        s.remove(shapes)
        val after = s.diagnose(main, mainSrc).filter { it.severity == "ERROR" }
        println("MULTI after removing Shapes.kt: ${after.map { it.message }.take(3)}")
        assertTrue(after.isNotEmpty(), "Rect is gone, so Main.kt no longer resolves")
    }
}
