package dev.ide.lang.kotlin

import dev.ide.lang.resolve.SourceDocProvider
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A library TYPE completes with its doc, as a member does: on a phone the popup offers docs only for items that
 * carry them, and types (`ScrollView`) used to carry none even with the platform docs bundled.
 */
class KotlinTypeDocCompletionTest {

    private fun complete(code: String) = runBlocking { analyzer.completeAtCaret(srcDir, "C.kt", code) }.items

    @Test
    fun aLibraryTypeCarriesItsClassDoc() {
        val items = complete("package demo\nval s: StringBuf|")
        val item = items.firstOrNull { it.label == "StringBuffer" }
            ?: error("no StringBuffer item; got ${items.take(20).map { it.label + "/" + (it.symbol as? dev.ide.lang.kotlin.symbols.KotlinSymbol)?.let { s -> (s.type as? dev.ide.lang.kotlin.symbols.KotlinType)?.qualifiedName } }}")
        assertTrue(item.documentation?.contains("A thread-safe, mutable sequence of characters.") == true, "got '${item.documentation}'")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Placeholder.kt" to "package demo\n"))
        private val provider = object : SourceDocProvider {
            override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? = null
            // Kotlin surfaces the JDK class under its `kotlin.text` alias, as the symbol model names it.
            override fun classDoc(fqn: String): String? =
                if (fqn == "kotlin.text.StringBuffer" || fqn == "java.lang.StringBuffer") "A thread-safe, mutable sequence of characters." else null
        }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).also { it.sourceDocProvider = provider }
    }
}
