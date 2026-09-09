package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compiler/runtime-implementation callables (Kotlin's `kotlin.jvm.internal`, `kotlin.coroutines.jvm.internal`,
 * `kotlin.internal`, `kotlin.reflect.jvm.internal`) are public in bytecode but not user-facing API — e.g.
 * `kotlin.jvm.internal.PrimitiveSpreadBuilder.getSize`, which was mis-keyed as a `kotlin.Any` extension and
 * leaked into completion. They must be filtered out, while real stdlib `Any` extensions (`let`/`also`) stay.
 */
class KotlinInternalApiFilterTest {

    private fun items(code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items

    @Test
    fun internalImplementationExtensionsAreHidden() {
        val items = items("package demo\nclass C { fun m() { this.| } }")
        val internal = items.mapNotNull { it.symbol as? KotlinSymbol }.filter { s ->
            val pkg = s.packageName ?: s.declaringClassFqn?.substringBeforeLast('.', "")
            pkg != null && (pkg.startsWith("kotlin.jvm.internal") || pkg.startsWith("kotlin.internal") ||
                pkg.startsWith("kotlin.coroutines.jvm.internal") || pkg.startsWith("kotlin.reflect.jvm.internal"))
        }
        assertTrue(internal.isEmpty(), "internal-impl callables must not appear; got ${internal.map { it.name + "@" + (it.packageName ?: it.declaringClassFqn) }}")
        assertTrue("getSize" !in items.map { it.symbol?.name ?: it.label }, "the kotlin.jvm.internal getSize leak must be gone")
    }

    @Test
    fun realStdlibAnyExtensionsStillAppear() {
        // The filter must not over-reach: public stdlib Any extensions remain.
        val labels = items("package demo\nclass C { fun m() { this.| } }").map { it.symbol?.name ?: it.label }
        assertTrue("let" in labels && "also" in labels && "apply" in labels, "stdlib Any extensions should remain; got ${labels.take(20)}")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
