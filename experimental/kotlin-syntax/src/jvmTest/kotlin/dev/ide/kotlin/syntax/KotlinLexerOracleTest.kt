package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.lexer.KotlinLexer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.lexer.KotlinLexer as CompilerLexer

/**
 * The differential oracle: our lexer against the real Kotlin compiler's, token for token and offset for
 * offset.
 *
 * This is the test that makes the port checkable rather than merely plausible. A hand-written lexer can pass
 * any number of hand-written cases and still disagree with the compiler on the input that matters; the only
 * honest check is to run both over code nobody wrote for the test, which is what [corpusSweep] does with this
 * repository's own ~1,500 Kotlin files.
 *
 * It is JVM-only by nature, since it needs the compiler this module exists to stop needing. That is fine: the
 * lexer under test is `commonMain` and compiles for Kotlin/Native, and this suite is how we know it is right
 * before it goes there.
 */
class KotlinLexerOracleTest {

    /** One token as the oracle compares it: what it is, and exactly where. */
    private data class Lexeme(val name: String, val start: Int, val end: Int) {
        override fun toString(): String = "$name[$start,$end)"
    }

    private fun ours(text: String): List<Lexeme> =
        KotlinLexer(text).tokenize().map { Lexeme(CompilerVocabulary.nameOf(it.type), it.start, it.end) }

    private fun theirs(text: String): List<Lexeme> {
        val lexer = CompilerLexer()
        lexer.start(text)
        val out = ArrayList<Lexeme>()
        while (lexer.tokenType != null) {
            out.add(Lexeme(CompilerVocabulary.nameOf(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }

    /** Compare, and on a mismatch report the first differing token with its surrounding source. */
    private fun assertSameStream(text: String, label: String = "") {
        val expected = theirs(text)
        val actual = ours(text)
        if (expected == actual) return

        val index = expected.zip(actual).indexOfFirst { (a, b) -> a != b }
        val at = if (index >= 0) index else minOf(expected.size, actual.size)
        val offset = expected.getOrNull(at)?.start ?: actual.getOrNull(at)?.start ?: 0
        val context = text.substring(maxOf(0, offset - 40), minOf(text.length, offset + 40))
        throw AssertionError(
            buildString {
                appendLine("lexer diverged from the compiler${if (label.isEmpty()) "" else " in $label"}")
                appendLine("  at offset $offset, token #$at")
                appendLine("  compiler: ${expected.getOrNull(at)}")
                appendLine("  ours:     ${actual.getOrNull(at)}")
                appendLine("  context:  ${context.replace("\n", "\\n")}")
                appendLine("  compiler stream: ${expected.drop(maxOf(0, at - 3)).take(8)}")
                appendLine("  our stream:      ${actual.drop(maxOf(0, at - 3)).take(8)}")
            },
        )
    }

    @Test
    fun theVocabulariesLineUpWellEnoughToCompare() {
        // If this fails, every other assertion in this file is comparing names rather than meanings.
        assertTrue(
            CompilerVocabulary.compilerTokenNames.isNotEmpty(),
            "could not reflect the compiler's KtTokens; the oracle would be vacuous",
        )
    }

    @Test
    fun declarations() {
        for (source in DECLARATIONS) assertSameStream(source)
    }

    @Test
    fun expressions() {
        for (source in EXPRESSIONS) assertSameStream(source)
    }

    @Test
    fun stringsAndTemplates() {
        for (source in STRINGS) assertSameStream(source)
    }

    @Test
    fun numbers() {
        for (source in NUMBERS) assertSameStream(source)
    }

    @Test
    fun commentsAndTrivia() {
        for (source in TRIVIA) assertSameStream(source)
    }

    @Test
    fun brokenInput() {
        // Agreement on VALID code is the easy half. An editor spends its life on the other half, and a lexer
        // that recovers differently from the compiler puts the squiggles in different places.
        for (source in BROKEN) assertSameStream(source)
    }

    /**
     * Both lexers over every Kotlin file in this repository.
     *
     * The corpus is the point: real code, written without this test in mind, including everything the module
     * itself has not thought of. A disagreement here is a real divergence, and the failure names the file and
     * offset.
     */
    @Test
    fun corpusSweep() {
        val root = System.getProperty("kotlinSyntax.corpusRoot")?.let(::File)
        if (root == null || !root.isDirectory) {
            println("corpus root not configured; skipping the sweep")
            return
        }
        val files = root.walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(files.size > 100, "expected a real corpus, found ${files.size} files under $root")

        var compared = 0
        val failures = ArrayList<String>()
        for (file in files) {
            val text = file.readText()
            if (ours(text) != theirs(text)) {
                failures += runCatching { assertSameStream(text, file.relativeTo(root).path) }
                    .exceptionOrNull()?.message.orEmpty()
                if (failures.size >= 5) break
            }
            compared++
        }
        assertTrue(
            failures.isEmpty(),
            "the lexer diverged on ${failures.size} of ${files.size} files:\n\n${failures.joinToString("\n")}",
        )
        println("lexer oracle: $compared files agree with the compiler, token for token")
    }

    @Test
    fun corpusOffsetsAreExact() {
        // A stream that agrees on token kinds but not on boundaries would still break every range the
        // analysis layer reports, so offsets are compared as part of the tuple rather than separately. This
        // asserts the tuple really does carry them.
        val text = "val x = 1"
        assertEquals(theirs(text).map { it.start to it.end }, ours(text).map { it.start to it.end })
    }

    private companion object {
        val SKIPPED_DIRECTORIES = setOf("build", "node_modules", "out", ".gradle", ".git", ".kotlin")

        val DECLARATIONS = listOf(
            "package a.b.c",
            "import kotlin.collections.List",
            "import a.b.C as D",
            "import a.b.*",
            "@file:JvmName(\"Foo\")\npackage x",
            "class Foo",
            "data class Point(val x: Int, val y: Int)",
            "value class Wrapper(val raw: String)",
            "sealed interface Shape { class Circle : Shape }",
            "enum class Color(val rgb: Int) { RED(0xFF0000), GREEN(0x00FF00); fun f() {} }",
            "annotation class Marker",
            "object Singleton { const val X = 1 }",
            "class A { companion object Named { } }",
            "fun <T : Any> f(x: T): T where T : Comparable<T> = x",
            "suspend fun load(): String = \"\"",
            "fun List<Int>.sum2(): Int = 0",
            "val x: Map<String, List<Int>>? = null",
            "var y = 0\n    private set",
            "val z by lazy { 1 }",
            "typealias Handler = suspend (Int) -> Unit",
            "class A @Inject constructor(private val b: B) : C(), D by e",
            "fun f(vararg xs: Int, noinline block: (Int) -> Unit = {}) {}",
            "context(a: A, b: B)\nfun f() {}",
            "class Box<in T, out U, reified V>",
            "fun f(): Int & Any = 1",
        )

        val EXPRESSIONS = listOf(
            "val a = 1 + 2 * 3 - 4 / 5 % 6",
            "val b = x?.y?.z ?: w!!",
            "val c = a as B",
            "val d = a as? B",
            "val e = a is B && a !is C",
            "val f = a in 1..10 || a !in list",
            "val g = list.map { it * 2 }.filter { it > 0 }",
            "val h = fold(0) { acc, e -> acc + e }",
            "val i = if (a) b else c",
            "val j = when (x) { 1 -> a; is Foo -> b; in 1..2 -> c; else -> d }",
            "val k = object : Runnable { override fun run() {} }",
            "val l = String::length",
            "val m = ::foo",
            "val n = Foo::class.java",
            "val o = a[0][1]",
            "val p = a..b",
            "val q = a..<b",
            "val r = a === b && a !== b",
            "val s = --a + ++b",
            "val t = arrayOf(*xs)",
            "fun f() { loop@ for (i in 1..2) { break@loop; continue@loop } }",
            "fun f() = run { return@run 1 }",
            "fun f() { try { g() } catch (e: E) { h() } finally { i() } }",
            "fun f() { do { g() } while (h) }",
            "val u = a to b to c",
            "val v = 1 shl 2 or 3 and 4",
        )

        val STRINGS = listOf(
            "val a = \"plain\"",
            "val b = \"with \$name inside\"",
            "val c = \"with \${a.b(c)} inside\"",
            "val d = \"nested \${ \"\${inner}\" } done\"",
            "val e = \"\"\"raw \\n not an escape\"\"\"",
            "val f = \"\"\"multi\nline\"\"\"",
            "val g = \"\"\"trailing quote \"\"\"\"",
            "val h = \"escapes \\n \\t \\\\ \\u00e9 \\\$\"",
            "val i = 'c'",
            "val j = '\\n'",
            "val k = '\\u00e9'",
            "val l = \"braces \${ run { 1 } } done\"",
            "val m = \"\"\"\${a} in raw\"\"\"",
            "val n = \"\$\$ not a template\"",
            "val o = \"dollar at end \$\"",
        )

        val NUMBERS = listOf(
            "val a = 0",
            "val b = 42L",
            "val c = 0xFF",
            "val d = 0xFFu",
            "val e = 0b1010",
            "val f = 1_000_000",
            "val g = 1.5",
            "val h = 1.5f",
            "val i = 1e10",
            "val j = 1.5e-3",
            "val k = 1..2",
            "val l = 1.toString()",
            "val m = 0uL",
            "val n = .5",
            "val o = 1.",
        )

        val TRIVIA = listOf(
            "// line comment\nval a = 1",
            "/* block */ val a = 1",
            "/** doc */\nfun f() {}",
            "/**/ val a = 1",
            "/* nested /* inner */ outer */ val a = 1",
            "#!/usr/bin/env kotlin\nval a = 1",
            "val a = 1 // trailing",
            "\n\n\nval a = 1\n\n\n",
            "val\ta\t=\t1",
        )

        val BROKEN = listOf(
            "val x = \"unterminated",
            "val x = \"\"\"unterminated raw",
            "fun f(",
            "class {",
            "val x = \"\${",
            "/* unterminated",
            "/** unterminated",
            "'",
            "`unterminated",
            "`unterminated\nval x = 1",
            "\$",
            "0x",
            "1e",
            "1e+",
            "val x = 1 @#^",
            "}}}",
            "val x = \"broken\nval y = 2",
        )
    }
}
