package dev.ide.build.jvm.run

import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramIo
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A console program calling a `kotlinx/` library the IDE can't supply by name runs the project's own copy.
 *
 * `kotlinx/` is bridged to the IDE's classes, and on the R8-minified app (3.44.0 on) the kotlinx-collections-
 * immutable it ships is renamed throughout, so `persistentListOf()` had nothing to bridge to and the run died.
 * The library here is generated, so it is absent from the test JVM exactly as a renamed one is absent on device.
 */
class LibraryHostFallbackRunTest {

    @Test fun aKotlinxLibraryTheHostLacksRunsFromTheProjectsJar() {
        withTempDir("host-fallback") { tmp ->
            val jar = tmp.resolve("program.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                out.add("$LIB.class", library())
                out.add("$MAIN.class", main())
            }
            val io = Recorder()

            val code = runBlocking { VmProgramInterpreter().run(InterpretRunRequest(listOf(jar), MAIN.replace('/', '.')), io) }

            assertEquals(0, code, "the run failed:\n${io.text()}")
            assertTrue(GREETING in io.text(), "the library's answer must reach the console: ${io.text()}")
        }
    }

    /** `kotlinx.r8gone.Greeter { static String greet() }`. */
    private fun library(): ByteArray = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).run {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, LIB, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "greet", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(GREETING)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }

    /** `demo.Main { static void main(String[]) { System.out.println(Greeter.greet()); } }`. */
    private fun main(): ByteArray = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).run {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, MAIN, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            visitMethodInsn(Opcodes.INVOKESTATIC, LIB, "greet", "()Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }

    private fun JarOutputStream.add(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private class Recorder : ProgramIo {
        private val out = StringBuilder()
        override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun stdout(text: String) { synchronized(out) { out.append(text) } }
        fun text(): String = synchronized(out) { out.toString() }
    }

    private companion object {
        const val LIB = "kotlinx/r8gone/Greeter"
        const val MAIN = "demo/Main"
        const val GREETING = "interpreted from the project's jar"
    }
}
