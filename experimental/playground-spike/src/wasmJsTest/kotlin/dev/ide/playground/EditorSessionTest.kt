package dev.ide.playground

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Completion and diagnostics off the JVM, over the real standard library. */
class EditorSessionTest {
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
            val r = s.complete(textWithCaret.replace("|", ""), offset)
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

        val bad = s.diagnose("package demo\nfun main() {\n  val x: Int = \"text\"\n  nope(1)\n}\n")
        println("EDITOR diagnostics: " + bad.map { "${it.severity} ${it.message}" })
        assertTrue(bad.any { it.severity == "ERROR" }, "type mismatch / unresolved are errors; got $bad")

        val good = s.diagnose("package demo\nfun main() {\n  println(listOf(1, 2).map { it * 2 })\n}\n")
        println("EDITOR clean-program diagnostics: " + good.map { "${it.severity} ${it.message}" })
        assertTrue(good.none { it.severity == "ERROR" }, "a correct program has no errors; got $good")
    }

    @Test
    fun semanticTokensAndInlayHints() = runTest {
        val s = session()
        val src = "package demo\n\nclass Box(val size: Int) {\n    fun grow(by: Int) = Box(size + by)\n}\n\nfun main() {\n    val xs = listOf(1, 2, 3)\n    val box = Box(xs.size).grow(2)\n    println(xs.map { it * 2 })\n}\n"
        val tokens = s.semanticTokens(src)
        val byKind = tokens.groupBy { it.kind }.mapValues { (_, v) -> v.map { src.substring(it.from, it.to) }.distinct() }
        println("EDITOR semantic tokens (${tokens.size}): $byKind")
        assertTrue(tokens.isNotEmpty(), "the highlighter classifies identifiers")
        assertTrue(byKind.keys.any { it == "class" } && byKind.keys.any { it.contains("function") || it == "method" }, "classes and functions are told apart; got ${byKind.keys}")

        val hints = s.inlayHints(src, 0, src.length)
        println("EDITOR inlay hints (${hints.size}): " + hints.map { "${it.kind}@${it.offset} '${it.parts.joinToString("")}'" })
        assertTrue(hints.any { it.kind == "TYPE" && it.parts.joinToString("").contains("List") }, "val xs gets its inferred type; got $hints")
    }

    /** What the page's analysis daemon does on load: the FIRST calls on a fresh session are semantic, then diagnostics. */
    @Test
    fun theFirstCallOnAFreshSessionAlreadyAnswers() = runTest {
        val s = session()
        val src = "package demo\n\ndata class Point(val x: Int, val y: Int)\n\nfun main() {\n    val points = listOf(Point(1, 2), Point(3, 4))\n    println(points.maxByOrNull { it.x })\n}\n"
        val tokens = try { s.semanticTokens(src) } catch (e: Throwable) { println("EDITOR FIRST semantic THREW ${e::class.simpleName}: ${e.message}"); throw e }
        println("EDITOR FIRST semantic: ${tokens.size} tokens")
        val hints = try { s.inlayHints(src, 0, src.length) } catch (e: Throwable) { println("EDITOR FIRST hints THREW ${e::class.simpleName}: ${e.message}"); throw e }
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
            val hint = s.inlayHints(text, 0, text.length).filter { it.kind == "TYPE" }.map { it.parts.joinToString("") }
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
        val hints = s.inlayHints(src, 0, src.length).filter { it.kind == "TYPE" }.map { it.parts.joinToString("") }
        println("EDITOR SNIPPET hints: $hints")
        assertTrue(": List<Int>" in hints && hints.none { it.contains("Nothing") }, "sum is a List<Int>; got $hints")
    }
}
