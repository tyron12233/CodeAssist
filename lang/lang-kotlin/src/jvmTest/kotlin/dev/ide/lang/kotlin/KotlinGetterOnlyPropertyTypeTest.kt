package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A getter-only property with no declared type (`val cache get() = caches.callTargets`).
 *
 * Its type was recorded as nothing at all -- the source model read only `initializer`, which such a property
 * does not have -- so every use of it was unknown and everything downstream backed off silently.
 *
 * KNOWN REMAINDER: `cache[key]?.let { return it }` inside an EXTENSION function still types `it` as the
 * extension receiver. The property and the indexing are now typed correctly (the two cases below), so what
 * is left is the lambda's `it` in that one position. Two guards were tried and reverted: binding `it`
 * untyped when the lambda's shape is unknown, and making a shadowed local return unknown instead of falling
 * through. Both are defensible on their own and neither fixed this, so neither was kept.
 */
class KotlinGetterOnlyPropertyTypeTest {

    @Test
    fun aGetterOnlyPropertyCarriesItsInferredType() = clean(
        """
        class Caches { val entries = HashMap<String, List<Int>>() }
        class Holder(val caches: Caches) {
            val cache get() = caches.entries
        }
        fun Holder.f() {
            val a: HashMap<String, List<Int>> = cache
            println(a)
        }
        """,
    )

    @Test
    fun indexingThroughItIsTyped() = clean(
        """
        class Caches { val entries = HashMap<String, List<Int>>() }
        class Holder(val caches: Caches) {
            val cache get() = caches.entries
        }
        fun Holder.f(key: String) {
            val b: List<Int>? = cache[key]
            println(b)
        }
        """,
    )

    private fun clean(code: String) = runBlocking {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        val errors = a.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
