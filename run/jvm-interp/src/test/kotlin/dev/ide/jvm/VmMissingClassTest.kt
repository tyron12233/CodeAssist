package dev.ide.jvm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.V1_6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A bridged class the host does not have is a [NoClassDefFoundError] the program can catch, as on a JVM, not a
 * failure of the VM itself: a test runner's `catch (Throwable)` around one test must survive it.
 */
class VmMissingClassTest {

    private val owner = "dev/ide/jvm/gen/MissingProbe"

    /** `static String <name>() { try { <access>; return "ok"; } catch (Throwable t) { return t.getClass().getName(); } }` */
    private fun probe(cw: ClassWriter, name: String, access: (org.objectweb.asm.MethodVisitor) -> Unit) {
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "()Ljava/lang/String;", null, null)
        mv.visitCode()
        val start = Label(); val end = Label(); val handler = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
        mv.visitLabel(start)
        access(mv)
        mv.visitLdcInsn("ok")
        mv.visitLabel(end)
        mv.visitInsn(ARETURN)
        mv.visitLabel(handler)
        mv.visitVarInsn(ASTORE, 0)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun bytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_6, ACC_PUBLIC, owner, null, "java/lang/Object", null)
        probe(cw, "readField") { mv -> mv.visitFieldInsn(GETSTATIC, "java/nope/Absent", "X", "I"); mv.visitInsn(POP) }
        probe(cw, "callMethod") { mv -> mv.visitMethodInsn(INVOKESTATIC, "java/nope/Absent", "f", "()V", false) }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun aMissingBridgedClassIsCatchable() {
        val generated = bytes()
        val fallback = ClassBytesSource.fromClasspath()
        val vm = Vm(ClassBytesSource { name -> if (name == owner) generated else fallback.bytesFor(name) })
        assertEquals("java.lang.NoClassDefFoundError", vm.invokeStatic(owner, "readField", "()Ljava/lang/String;", emptyList()))
        assertEquals("java.lang.NoClassDefFoundError", vm.invokeStatic(owner, "callMethod", "()Ljava/lang/String;", emptyList()))
    }
}
