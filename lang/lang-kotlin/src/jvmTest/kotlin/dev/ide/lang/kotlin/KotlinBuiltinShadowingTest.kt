package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A DECLARATION shadows a built-in of the same name.
 *
 * The built-ins reach a file through the outermost scope there is, the implicit `kotlin.*` /
 * `kotlin.collections.*` star imports, so anything declared nearer than that wins. Classifier resolution put
 * them FIRST instead, ahead of even a nested class, and `Iterator`, `Comparator`, `Function`, `Number`,
 * `Enum`, `Annotation` and `Throwable` are all names an ordinary project declares. Every use of such a type
 * was then checked against the built-in: found by sweeping the checkers over the Kotlin standard library,
 * whose own `UByteArray` declares a nested `class Iterator` and calls `Iterator(storage)` — reported as
 * "cannot create an instance of abstract class or interface 'Iterator'", on code that compiles.
 */
class KotlinBuiltinShadowingTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    @Test
    fun aNestedClassShadowsTheBuiltinOfTheSameName() {
        val diags = errors(
            "NestedIterator.kt",
            """
            package demo
            class Bag(private val items: List<String>) {
                fun each(): kotlin.collections.Iterator<String> = Iterator(items)
                private class Iterator(private val items: List<String>) : kotlin.collections.Iterator<String> {
                    private var i = 0
                    override fun hasNext(): Boolean = i < items.size
                    override fun next(): String = items[i++]
                }
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "the nested class is what `Iterator(items)` names; got ${diags.map { it.message }}")
    }

    @Test
    fun aSamePackageClassShadowsTheBuiltinOfTheSameName() {
        val diags = errors(
            "OwnComparator.kt",
            "package demo\nclass Comparator(val key: String)\nfun make(): Comparator = Comparator(\"x\")\nfun read(c: Comparator): String = c.key\n",
        )
        assertTrue(diags.isEmpty(), "`Comparator` here is the file's own class; got ${diags.map { it.message }}")
    }

    @Test
    fun anUnshadowedBuiltinStillResolvesToTheBuiltin() {
        val diags = errors("PlainList.kt", "package demo\nfun f(xs: List<String>): Int = xs.size\n")
        assertTrue(diags.isEmpty(), "`List` with nothing shadowing it is still the built-in; got ${diags.map { it.message }}")
    }

    @Test
    fun anExplicitImportStillDoesNotDisplaceABuiltinType() {
        // The Compose-icon shape: `import ….filled.List` must not turn `List<String>` into the icon object,
        // which is why the built-ins keep their place AHEAD of the explicit-import step.
        val diags = errors(
            "IconList.kt",
            "package demo\nimport demo.icons.filled.List\nfun f(xs: List<String>): Int = xs.size\n",
        )
        assertTrue(
            diags.none { it.code == KotlinDiagnosticCodes.UNRESOLVED },
            "`.size` must still be checked against kotlin.collections.List; got ${diags.map { it.message }}",
        )
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
