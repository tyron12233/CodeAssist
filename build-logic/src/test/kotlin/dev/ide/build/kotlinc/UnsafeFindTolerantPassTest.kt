package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [UnsafeFindTolerantPass] over a stand-in for the platform's `com.intellij.util.containers.Unsafe`: `find`
 * returns null instead of throwing, and `getAndAddInt` (whose `sun.misc.Unsafe` lookup ART denies as hidden API)
 * no longer goes through its null MethodHandle but straight to the `VarHandleCompat` CAS loop.
 */
class UnsafeFindTolerantPassTest {

    private val pass = UnsafeFindTolerantPass()
    private val owner = "com/intellij/util/containers/Unsafe"
    private val desc = UnsafeFindTolerantPass.GET_AND_ADD_INT_DESC

    @Test
    fun getAndAddIntCallsTheShimInsteadOfTheDeniedHandle() {
        val out = ClassNode().also { ClassReader(runPass(wrapper())).accept(it, 0) }
        val m = out.methods.single { it.name == "getAndAddInt" }
        val calls = m.instructions.filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.size, "only the shim call remains; got ${calls.map { "${it.owner}.${it.name}" }}")
        val call = calls.single()
        assertEquals(Opcodes.INVOKESTATIC, call.opcode)
        assertEquals(UnsafeFindTolerantPass.SHIM, call.owner)
        assertEquals("getAndAddInt", call.name)
        assertEquals(desc, call.desc)
        assertTrue(m.tryCatchBlocks.isEmpty(), "the old handler covered instructions that are gone")
    }

    @Test
    fun findReturnsNullOnFailure() {
        val out = ClassNode().also { ClassReader(runPass(wrapper())).accept(it, 0) }
        val find = out.methods.single { it.name == "find" }
        assertTrue(find.tryCatchBlocks.any { it.type == "java/lang/Throwable" }, "find must catch every failure")
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        ClassReader(bytes).accept(pass.visitor(UnsafeFindTolerantPass.TARGET, writer), 0)
        return writer.toByteArray()
    }

    /** The wrapper's shape: a static MethodHandle per op, `find` resolving one, `getAndAddInt` invoking it. */
    private fun wrapper(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null)
        val mh = "java/lang/invoke/MethodHandle"
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "getAndAddInt", "L$mh;", null, null).visitEnd()
        cw.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "find",
            "(Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;)L$mh;", null, arrayOf("java/lang/Exception"),
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/Error")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Error", "<init>", "()V", false)
            visitInsn(Opcodes.ATHROW)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "getAndAddInt", desc, null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, owner, "getAndAddInt", "L$mh;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.LLOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, mh, "invokeExact", desc, false)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
