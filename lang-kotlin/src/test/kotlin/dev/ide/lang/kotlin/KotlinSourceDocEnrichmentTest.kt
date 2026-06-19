package dev.ide.lang.kotlin

import dev.ide.lang.resolve.SourceDocProvider
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * When SOURCES are attached, completing a Java/Android API from a `.kt` file shows REAL parameter names and
 * javadoc instead of the `p0`/`p1` placeholders bytecode leaves behind. A synthetic `com.example.Widget` with
 * a parameterized method on the classpath (no debug names → `p0`/`p1`) is enriched by a stub [SourceDocProvider]
 * standing in for the source-doc index.
 */
class KotlinSourceDocEnrichmentTest {

    private fun complete(code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items

    @Test
    fun realParameterNamesReplaceBytecodePlaceholders() {
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.set| }")
            .first { it.symbol?.name == "setText" }
        assertTrue("message" in item.label, "real param name in label; got '${item.label}'")
        assertTrue("flags" in item.label, "real param name in label; got '${item.label}'")
        assertTrue("p0" !in item.label && "p1" !in item.label, "no bytecode placeholders; got '${item.label}'")
    }

    @Test
    fun javadocReachesTheCompletionItem() {
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.set| }")
            .first { it.symbol?.name == "setText" }
        assertEquals("Sets the widget text.", item.documentation)
    }

    @Test
    fun withoutSourcesNoNamesNoDocs() {
        // A method the provider knows nothing about keeps the bytecode placeholder display and no docs.
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.un| }")
            .first { it.symbol?.name == "untouched" }
        assertNotNull(item)
        assertTrue("p0" in item.label, "unenriched method keeps the placeholder; got '${item.label}'")
        assertEquals(null, item.documentation)
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())

        private val provider = object : SourceDocProvider {
            override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
                if (declaringFqn == "com.example.Widget" && methodName == "setText" && arity == 2)
                    SourceDocProvider.MethodDoc(listOf("message", "flags"), "Sets the widget text.")
                else null
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), widgetJar())))
            .also { it.sourceDocProvider = provider }

        /** A Java class with a 2-arg `setText(String, int)` and a 1-arg `untouched(String)` — bytecode, no names. */
        private fun widgetJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Widget", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "setText", "(Ljava/lang/String;I)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "untouched", "(Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            cw.visitEnd()
            val jar = Files.createTempFile("widget", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/example/Widget.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
