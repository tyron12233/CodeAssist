package dev.ide.playground

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Completion and diagnostics off the JVM, over the real standard library. */
class EditorSessionTest {
    private val P = "/project/Main.kt"

    private fun session(): EditorSession {
        mountHostFiles(VmClasspath.entries)
        val analysisJars = VmClasspath.entries.filter {
            val n = it.substringAfterLast('/')
            n.startsWith("kotlin-stdlib-") || n.startsWith("kotlinx-coroutines-core-jvm-")
        }
        return EditorSession(analysisJars).also { println("EDITOR index built in ${it.indexMillis} ms") }
    }

    @Test
    fun completionAndDiagnostics() = runTest {
        val s = session()

        suspend fun at(textWithCaret: String): Pair<List<String>, Long> {
            val offset = textWithCaret.indexOf('|')
            val r = s.complete(P, textWithCaret.replace("|", ""), offset)
            return r.items.map { it.label.substringBefore('(') } to r.millis
        }

        val (members, t1) = at("package demo\nfun main() {\n  val xs = listOf(1, 2, 3)\n  xs.|\n}")
        println("EDITOR member completion ${t1} ms: ${members.take(12)}")
        assertTrue("map" in members && "filter" in members && "size" in members, "List members; got ${members.take(30)}")

        val (bare, t2) = at("package demo\nfun main() {\n  printl|\n}")
        println("EDITOR bare-name completion ${t2} ms: ${bare.take(8)}")
        assertTrue("println" in bare, "println must complete by NAME; got ${bare.take(30)}")

        val (types, t3) = at("package demo\nfun main() {\n  val m: MutableL|\n}")
        println("EDITOR type completion ${t3} ms: ${types.take(8)}")
        assertTrue(types.any { it.startsWith("MutableList") }, "types by prefix; got ${types.take(30)}")

        val bad = s.diagnose(P, "package demo\nfun main() {\n  val x: Int = \"text\"\n  nope(1)\n}\n")
        println("EDITOR diagnostics: " + bad.map { "${it.severity} ${it.message}" })
        assertTrue(bad.any { it.severity == "ERROR" }, "type mismatch / unresolved are errors; got $bad")

        val good = s.diagnose(P, "package demo\nfun main() {\n  println(listOf(1, 2).map { it * 2 })\n}\n")
        println("EDITOR clean-program diagnostics: " + good.map { "${it.severity} ${it.message}" })
        assertTrue(good.none { it.severity == "ERROR" }, "a correct program has no errors; got $good")
    }

    @Test
    fun semanticTokensAndInlayHints() = runTest {
        val s = session()
        val src = "package demo\n\nclass Box(val size: Int) {\n    fun grow(by: Int) = Box(size + by)\n}\n\nfun main() {\n    val xs = listOf(1, 2, 3)\n    val box = Box(xs.size).grow(2)\n    println(xs.map { it * 2 })\n}\n"
        val tokens = s.semanticTokens(P, src)
        val byKind = tokens.groupBy { it.kind }.mapValues { (_, v) -> v.map { src.substring(it.from, it.to) }.distinct() }
        println("EDITOR semantic tokens (${tokens.size}): $byKind")
        assertTrue(tokens.isNotEmpty(), "the highlighter classifies identifiers")
        assertTrue(byKind.keys.any { it == "class" } && byKind.keys.any { it.contains("function") || it == "method" }, "classes and functions are told apart; got ${byKind.keys}")

        val hints = s.inlayHints(P, src, 0, src.length)
        println("EDITOR inlay hints (${hints.size}): " + hints.map { "${it.kind}@${it.offset} '${it.parts.joinToString("")}'" })
        assertTrue(hints.any { it.kind == "TYPE" && it.parts.joinToString("").contains("List") }, "val xs gets its inferred type; got $hints")
    }

    /** What the page's analysis daemon does on load: the FIRST calls on a fresh session are semantic, then diagnostics. */
    @Test
    fun theFirstCallOnAFreshSessionAlreadyAnswers() = runTest {
        val s = session()
        val src = "package demo\n\ndata class Point(val x: Int, val y: Int)\n\nfun main() {\n    val points = listOf(Point(1, 2), Point(3, 4))\n    println(points.maxByOrNull { it.x })\n}\n"
        val tokens = try { s.semanticTokens(P, src) } catch (e: Throwable) { println("EDITOR FIRST semantic THREW ${e::class.simpleName}: ${e.message}"); throw e }
        println("EDITOR FIRST semantic: ${tokens.size} tokens")
        val hints = try { s.inlayHints(P, src, 0, src.length) } catch (e: Throwable) { println("EDITOR FIRST hints THREW ${e::class.simpleName}: ${e.message}"); throw e }
        println("EDITOR FIRST hints: ${hints.size}: " + hints.map { it.parts.joinToString("") })
        assertTrue(tokens.isNotEmpty() && hints.isNotEmpty(), "first semantic=${tokens.size} hints=${hints.size}")
    }

    @Test
    fun lambdaResultTypeWithALabeledReturn() = runTest {
        val s = session()
        fun src(body: String) = "package demo\n\ndata class Point(val x: Int, val y: Int)\n\nfun main() {\n    val points = listOf(Point(1, 2), Point(3, 4))\n$body\n}\n"
        val cases = linkedMapOf(
            "plain last expression" to "    val a = points.map { it.x + it.y }",
            "destructuring then last expression" to "    val a = points.map {\n        val (x, y) = it\n        x + y\n    }",
            "labeled return as last statement" to "    val a = points.map {\n        val (x, y) = it\n        return@map x + y\n    }",
            "labeled return, no destructuring" to "    val a = points.map {\n        return@map it.x + it.y\n    }",
            "labeled return in a branch" to "    val a = points.map {\n        if (it.x > 1) return@map 1\n        it.y\n    }",
            "branch returns a String, last is Int" to "    val a = points.map {\n        if (it.x > 1) return@map \"big\"\n        it.y\n    }",
            "only a throw" to "    val a = points.map {\n        throw IllegalStateException()\n    }",
        )
        for ((label, body) in cases) {
            val text = src(body)
            val hint = s.inlayHints(P, text, 0, text.length).filter { it.kind == "TYPE" }.map { it.parts.joinToString("") }
            println("EDITOR LAMBDA $label -> $hint")
        }
    }

    /** The snippet that exposed the bug, verbatim: `sum` must be a List<Int>, not a List<Nothing>. */
    @Test
    fun theReportedSnippet() = runTest {
        val s = session()
        val src = """
            package demo

            data class Point(val x: Int, val y: Int) {
                fun dist2() = x * x + y * y
            }

            fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

            fun main() {
                val points = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
               
                val sum = points.map {
                    val (x, y) = it
                    
                    return@map x + y
                }
                
                println(sum)
            }
        """.trimIndent()
        val hints = s.inlayHints(P, src, 0, src.length).filter { it.kind == "TYPE" }.map { it.parts.joinToString("") }
        println("EDITOR SNIPPET hints: $hints")
        assertTrue(": List<Int>" in hints && hints.none { it.contains("Nothing") }, "sum is a List<Int>; got $hints")
    }

    @Test
    fun theOtherEditorFeatures() = runTest {
        val s = session()
        val src = "package demo\n\nimport kotlin.collections.ArrayList\nimport java.util.Random\n\ndata class Point(val x:Int,val y:Int) {\n    fun dist2() = x*x + y*y\n}\n\nfun main() {\n    val p = Point(1,2)\n    println(p.dist2())\n    listOf(1, 2).map { it }\n}\n"
        fun at(marker: String, delta: Int = 0) = src.indexOf(marker) + delta

        println("EDITOR CALL folds: " + s.call("folds", P, src, 0, 0))
        println("EDITOR CALL signature: " + s.call("signature", P, src, at("println(") + 8, 0))
        println("EDITOR CALL quickdoc listOf: " + s.call("quickdoc", P, src, at("listOf") + 2, 0).take(300))
        println("EDITOR CALL quickdoc Point: " + s.call("quickdoc", P, src, at("Point(1") + 2, 0).take(300))
        println("EDITOR CALL format: " + s.call("format", P, src, -1, -1).take(400))
        println("EDITOR CALL imports: " + s.call("imports", P, src, 0, 0))
        println("EDITOR CALL definition of Point at use: " + s.call("definition", P, src, at("Point(1") + 2, 0) + "  (declared at ${at("class Point") + 11})")
        println("EDITOR CALL definition of println (library): " + s.call("definition", P, src, at("println") + 2, 0))
        println("EDITOR CALL expand: " + s.call("expand", P, src, at("dist2()") + 1, at("dist2()") + 1))

        assertTrue(s.call("folds", P, src, 0, 0).startsWith("[["), "the class and function bodies fold")
        assertTrue(s.call("signature", P, src, at("println(") + 8, 0) != "null", "signature help inside println(")
        assertTrue(s.call("definition", P, src, at("Point(1") + 2, 0).contains("offset"), "a project type navigates")
        assertTrue(s.call("definition", P, src, at("println") + 2, 0) == "null", "a library function has no source to open")
        assertTrue(s.call("format", P, src, -1, -1).startsWith("[["), "badly spaced code is reformatted")
        assertTrue(s.call("imports", P, src, 0, 0).startsWith("[["), "unused imports are removed")
    }

    @Test
    fun quickFixesUsagesAndRename() = runTest {
        val s = session()
        val src = "package demo\n\nclass Point(val x: Int, val y: Int) {\n    fun dist2() = x * x + y * y\n}\n\nfun main() {\n    val p = Point(1, 2)\n    val q = Point(3, 4)\n    println(p.dist2() + q.dist2() + p.x)\n    val r = Random()\n}\n"
        fun at(marker: String, delta: Int = 0) = src.indexOf(marker) + delta

        println("EDITOR FIX actions at Random: " + s.call("actions", P, src, at("Random()") + 2, 0))
        println("EDITOR FIX applyaction 0: " + s.call("applyaction", P, src, at("Random()") + 2, 0))
        println("EDITOR FIX usages of local p: " + s.call("usages", P, src, at("val p") + 4, 0))
        println("EDITOR FIX usages of property x: " + s.call("usages", P, src, at("val x") + 4, 0))
        println("EDITOR FIX prepare p: " + s.call("prepare", P, src, at("val p") + 4, 0))
        println("EDITOR FIX prepare class Point: " + s.call("prepare", P, src, at("class Point") + 8, 0))
        println("EDITOR FIX prepare println (library): " + s.call("prepare", P, src, at("println") + 2, 0))
        println("EDITOR FIX rename p -> origin: " + s.call("rename:origin", P, src, at("val p") + 4, 0))
        println("EDITOR FIX rename Point -> Vec: " + s.call("rename:Vec", P, src, at("class Point") + 8, 0))

        assertTrue(s.call("actions", P, src, at("Random()") + 2, 0).contains("mport"), "an unresolved name offers an import")
        assertTrue(s.call("usages", P, src, at("val p") + 4, 0).startsWith("[["), "usages of a local")
        assertTrue(s.call("prepare", P, src, at("val p") + 4, 0).contains("oldName"), "a local is renameable")
        assertTrue(s.call("prepare", P, src, at("println") + 2, 0) == "null", "a library function is not")
        assertTrue(s.call("rename:origin", P, src, at("val p") + 4, 0).startsWith("[["), "rename answers with edits")
    }
}
