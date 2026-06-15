package dev.ide.lang.kotlin

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
 * Kotlin ↔ Java interop in completion + resolution, with visibility enforced. A synthetic
 * `com.example.JavaThing` (static + instance + private members) on the classpath verifies a Kotlin file
 * sees Java types, their STATIC members on type access and instance members on a value, and that PRIVATE
 * members never show. A Kotlin source class with private members verifies the Kotlin-visibility filter.
 */
class JavaInteropTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }

    @Test
    fun kotlinSeesJavaStaticMembersOnTypeAccess() {
        val items = labels("import com.example.JavaThing\nfun f() { JavaThing.| }")
        assertTrue("staticHello" in items, "Java static method on type access; got $items")
        assertTrue("CONSTANT" in items, "Java static field on type access; got $items")
        assertTrue("instanceHello" !in items, "instance member must NOT show on type access")
        assertTrue("secret" !in items, "private member must never show")
    }

    @Test
    fun kotlinSeesJavaInstanceMembersOnValue() {
        val items = labels("import com.example.JavaThing\nfun f(j: JavaThing) { j.| }")
        assertTrue("instanceHello" in items, "Java instance member on a value; got $items")
        assertTrue("staticHello" !in items, "static member must NOT show on an instance")
        assertTrue("secret" !in items, "private member must never show")
    }

    @Test
    fun kotlinPrivateMembersHiddenOnAccess() {
        val items = labels("package demo\nfun f(s: Secret) { s.| }")
        assertTrue("shownFun" in items && "shownProp" in items, "public Kotlin members shown; got $items")
        assertTrue("hiddenFun" !in items, "private Kotlin function must be hidden; got $items")
        assertTrue("hiddenProp" !in items, "private Kotlin property must be hidden; got $items")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Secret.kt" to """
                    package demo
                    class Secret {
                        private fun hiddenFun() {}
                        fun shownFun() {}
                        private val hiddenProp = 1
                        val shownProp = 2
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), javaThingJar())))

        /** A synthetic Java class with a static method+field, an instance method, and a private method. */
        private fun javaThingJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/JavaThing", null, "java/lang/Object", null)
            cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "CONSTANT", "I", null, 42).visitEnd()
            fun method(access: Int, name: String) = cw.visitMethod(access, name, "()V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "staticHello")
            method(Opcodes.ACC_PUBLIC, "instanceHello")
            method(Opcodes.ACC_PRIVATE, "secret")
            cw.visitEnd()
            val jar = Files.createTempFile("javathing", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/example/JavaThing.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
