package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [StringFormattedArtPass] turns `String.formatted` into `String.format`, and the rewritten class still runs. */
class StringFormattedArtPassTest {

    private val pass = StringFormattedArtPass()

    @Test
    fun handlesTheMcpSdkAndJacksonOnly() {
        assertTrue(pass.handles("io.modelcontextprotocol.spec.McpTransportSessionClosedException"))
        assertTrue(pass.handles("tools.jackson.databind.DeserializationContext"))
        assertFalse(pass.handles("dev.ide.agent.mcp.CodeAssistMcpServer"))
    }

    @Test
    fun rewritesFormattedToTheStaticFormat() {
        val out = runPass(synthetic())

        assertEquals(listOf("INVOKESTATIC java/lang/String.format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"), callsIn(out))
    }

    @Test
    fun theRewrittenMethodProducesTheSameString() {
        val out = runPass(synthetic())
        val loaded = object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass("io.modelcontextprotocol.Synthetic", out, 0, out.size)
        }.define()

        assertEquals("Session s1 closed", loaded.getMethod("message", String::class.java).invoke(null, "s1"))
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor("io.modelcontextprotocol.Synthetic", writer), 0)
        return writer.toByteArray()
    }

    /** `static String message(String id) { return "Session %s closed".formatted(id); }` */
    private fun synthetic(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "io/modelcontextprotocol/Synthetic", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "message", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
        mv.visitCode()
        mv.visitLdcInsn("Session %s closed")
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.AASTORE)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "formatted", "([Ljava/lang/Object;)Ljava/lang/String;", false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(5, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun callsIn(bytes: ByteArray): List<String> {
        val calls = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, methodName: String, methodDescriptor: String, isInterface: Boolean) {
                        calls += "${if (opcode == Opcodes.INVOKESTATIC) "INVOKESTATIC" else "OTHER"} $owner.$methodName$methodDescriptor"
                    }
                }
        }, 0)
        return calls
    }
}
