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
import kotlin.test.assertTrue

/**
 * Source-doc enrichment must reach INHERITED binary members (not only a receiver type's OWN members), and
 * the `super.member` path. `Base.onCreate(Bundle)` lives two levels up the hierarchy from the Kotlin class;
 * completing it via `this.` / `super.` should still splice the real parameter name from the stub provider.
 */
class KotlinInheritedEnrichmentTest {

    private fun complete(code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items

    @Test
    fun inheritedMemberIsEnriched() {
        val item = complete("import com.example.Derived\nfun f(d: Derived) { d.onCre| }")
            .first { it.symbol?.name == "onCreate" }
        assertTrue("bundle" in item.label, "real inherited param name; got '${item.label}'")
        assertTrue("p0" !in item.label, "no bytecode placeholder; got '${item.label}'")
    }

    @Test
    fun superMemberIsEnriched() {
        val code = "import com.example.Derived\nclass Act : Derived() { fun g() { super.onCre| } }"
        val item = complete(code).first { it.symbol?.name == "onCreate" }
        assertTrue("bundle" in item.label, "real super param name; got '${item.label}'")
        assertTrue("p0" !in item.label, "no bytecode placeholder; got '${item.label}'")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())

        private val provider = object : SourceDocProvider {
            override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
                if (declaringFqn == "com.example.Base" && methodName == "onCreate" && arity == 1)
                    SourceDocProvider.MethodDoc(listOf("bundle"), "Called when created.")
                else null
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), hierarchyJar())))
            .also { it.sourceDocProvider = provider }

        /** `Base { void onCreate(String) }` and `Derived extends Base` — bytecode, no param names. */
        private fun hierarchyJar(): Path {
            val base = ClassWriter(0).apply {
                visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Base", null, "java/lang/Object", null)
                visitMethod(Opcodes.ACC_PUBLIC, "onCreate", "(Ljava/lang/String;)V", null, null).apply {
                    visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
                }
                visitEnd()
            }.toByteArray()
            val derived = ClassWriter(0).apply {
                visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Derived", null, "com/example/Base", null)
                visitEnd()
            }.toByteArray()
            val jar = Files.createTempFile("hierarchy", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/example/Base.class")); zos.write(base); zos.closeEntry()
                zos.putNextEntry(ZipEntry("com/example/Derived.class")); zos.write(derived); zos.closeEntry()
            }
            return jar
        }
    }
}
