package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Redeclarations the compiler reports beyond two same-kind declarations: a property beside a classifier of its
 * name, an enum entry beside a property or another entry, a constructor `val` redeclared in the body, two
 * companions, and a type parameter listed twice. Each case keeps a legal neighbour clean.
 */
class KotlinRedeclarationTest {

    private fun conflicts(code: String): List<String> {
        val dir = tempProject(mapOf("Use.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        try {
            val doc = SnippetDoc(code, DiskFile(dir.resolve("Use.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == Severity.ERROR && it.code == KotlinDiagnosticCodes.CONFLICTING_DECLARATION }
                .map { code.substring(it.range.start, it.range.end) }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun propertyBesideAClassifierOfItsName() {
        val got = conflicts("package demo\nval A = 10\nobject A\nval String.B: Int get() = 1\nclass B\n")
        assertEquals(listOf("A", "A"), got, "the extension property B does not clash; got $got")
    }

    @Test
    fun enumEntriesShareThePropertyNamespace() {
        val got = conflicts("package demo\nenum class E { FOO, FOO }\nenum class F { BAR;\n  val BAR = 1\n  fun BAR() {} }\n")
        assertEquals(listOf("FOO", "FOO", "BAR", "BAR"), got, "got $got")
    }

    @Test
    fun constructorPropertyRedeclaredInTheBody() {
        val got = conflicts("package demo\nclass A(val x: Int, y: Int) {\n  val x = \"\"\n  val y = 2\n}\n")
        assertEquals(listOf("x", "x"), got, "a plain parameter `y` is not a property; got $got")
    }

    @Test
    fun twoCompanionObjects() {
        val got = conflicts("package demo\nclass A {\n  companion object {}\n  object Companion\n}\n")
        assertEquals(listOf("companion object", "Companion"), got, "got $got")
    }

    @Test
    fun constructorsWithTheSameParameterTypes() {
        val got = conflicts(
            "package demo\nclass A(x: String = \"\") {\n  constructor(x: String, y: Int) : this(x)\n" +
                "  constructor(y: String, z: Int, w: Int) : this(y)\n  constructor(q: String) : this()\n}\n"
        )
        assertEquals(listOf("(x: String = \"\")", "(q: String)"), got, "got $got")
    }

    @Test
    fun typeParameterListedTwice() {
        val got = conflicts("package demo\nfun <T, T> f() {}\nclass P<X, Y, X>\n")
        assertEquals(listOf("T", "T", "X", "X"), got, "got $got")
    }
}
