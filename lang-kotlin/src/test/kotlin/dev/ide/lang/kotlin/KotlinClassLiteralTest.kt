package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `X::class` (a class literal → `kotlin.reflect.KClass<X>`) and the `.java` JVM-interop extension
 * (`KClass<T>.java: Class<T>`) resolved + completed end-to-end.
 */
class KotlinClassLiteralTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    /** Infer the type of the expression whose source text is exactly [exprText] (e.g. `Main::class.java`). */
    private fun typeOf(code: String, exprText: String): KotlinType? {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val parsed = analyzer.incrementalParser.parseFull(doc)
        val start = code.indexOf(exprText)
        // Pre-order walk (parent before children) → the first exact text match is the outermost such node.
        val node = parsed.nodesIn(dev.ide.lang.dom.TextRange(start, start + exprText.length))
            .firstOrNull { it.text().toString() == exprText } ?: error("no node for '$exprText'")
        return analyzer.resolveType(node) as? KotlinType
    }

    @Test fun classLiteralOffersKClassMembers() {
        val items = labels("package demo\nfun f() { Main::class.sim| }")
        assertTrue("simpleName" in items, "Main::class should be KClass → simpleName; got $items")
    }

    @Test fun classLiteralOffersJavaExtension() {
        val items = labels("package demo\nfun f() { Main::class.ja| }")
        assertTrue("java" in items, "Main::class.java extension should be offered; got $items")
    }

    @Test fun classLiteralTypeIsKClassOfMain() {
        val t = typeOf("package demo\nfun f() { Main::class.java }", "Main::class")
        assertEquals("kotlin.reflect.KClass", t?.qualifiedName)
        assertEquals("demo.Main", (t?.typeArguments?.firstOrNull() as? KotlinType)?.qualifiedName)
    }

    @Test fun javaExtensionTypeIsClassOfMain() {
        // `Main::class.java` resolves through the `KClass<T>.java` extension to `Class<demo.Main>`.
        val t = typeOf("package demo\nfun f() { Main::class.java }", "Main::class.java")
        assertEquals("java.lang.Class", t?.qualifiedName)
        assertEquals("demo.Main", (t?.typeArguments?.firstOrNull() as? KotlinType)?.qualifiedName)
    }

    @Test fun boundClassReferenceTypeFromValue() {
        // `instance::class` (a bound reference on a value) → KClass<demo.Main>.
        val t = typeOf("package demo\nfun f() { val m = Main()\n  m::class.java }", "m::class")
        assertEquals("kotlin.reflect.KClass", t?.qualifiedName)
        assertEquals("demo.Main", (t?.typeArguments?.firstOrNull() as? KotlinType)?.qualifiedName)
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Main.kt" to "package demo\nclass Main { fun hello() {} }"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
