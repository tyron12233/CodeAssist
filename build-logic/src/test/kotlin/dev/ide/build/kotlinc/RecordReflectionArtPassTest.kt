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

/** [RecordReflectionArtPass] removes every API 34 record/sealed query on a `Class`, and the result still runs. */
class RecordReflectionArtPassTest {

    private val pass = RecordReflectionArtPass()

    @Test
    fun handlesJacksonAndTheMcpSdkOnly() {
        assertTrue(pass.handles("tools.jackson.databind.util.ClassUtil"))
        assertTrue(pass.handles("io.modelcontextprotocol.spec.McpSchema"))
        assertFalse(pass.handles("dev.ide.jvm.InterpretedReflection"))
    }

    @Test
    fun noRecordQueryIsLeftAndTheAnswersAreTheConstants() {
        val out = run(synthetic())
        assertEquals(emptyList(), classCallsIn(out), "every query must be gone")
        val loaded = object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass("tools.jackson.Synthetic", out, 0, out.size)
        }.define()
        // Asked of a real record and a real sealed type, which the unpatched calls would say yes to.
        assertEquals(false, loaded.getMethod("isRecord", Class::class.java).invoke(null, Sample::class.java))
        assertEquals(false, loaded.getMethod("isSealed", Class::class.java).invoke(null, Shape::class.java))
        assertEquals(null, loaded.getMethod("components", Class::class.java).invoke(null, Sample::class.java))
        assertEquals(null, loaded.getMethod("permitted", Class::class.java).invoke(null, Shape::class.java))
    }

    @JvmRecord
    private data class Sample(val x: Int)

    private sealed interface Shape

    private fun run(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor("tools.jackson.Synthetic", writer), 0)
        return writer.toByteArray()
    }

    /** Four static methods, each returning one of the queries on its `Class` argument. */
    private fun synthetic(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "tools/jackson/Synthetic", null, "java/lang/Object", null)
        fun method(name: String, call: String, descriptor: String, returns: Int) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "(Ljava/lang/Class;)" + descriptor.substringAfter(')'), null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", call, descriptor, false)
            mv.visitInsn(returns)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }
        method("isRecord", "isRecord", "()Z", Opcodes.IRETURN)
        method("isSealed", "isSealed", "()Z", Opcodes.IRETURN)
        method("components", "getRecordComponents", "()[Ljava/lang/reflect/RecordComponent;", Opcodes.ARETURN)
        method("permitted", "getPermittedSubclasses", "()[Ljava/lang/Class;", Opcodes.ARETURN)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun classCallsIn(bytes: ByteArray): List<String> {
        val calls = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, methodName: String, methodDescriptor: String, isInterface: Boolean) {
                        if (owner == "java/lang/Class") calls += methodName
                    }
                }
        }, 0)
        return calls
    }
}
