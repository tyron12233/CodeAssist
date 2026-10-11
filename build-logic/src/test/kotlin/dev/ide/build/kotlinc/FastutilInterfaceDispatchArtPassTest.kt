package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FastutilInterfaceDispatchArtPass] must turn every fastutil `invokeinterface` in IntelliJ's `MarkerOptionalData`
 * into an `invokestatic` on the shim (receiver first), leave other calls alone, and refuse a call it has no shim
 * for. Verified in bytecode against a synthetic class shaped like core-impl 251's `MarkerOptionalData`.
 */
class FastutilInterfaceDispatchArtPassTest {

    private val pass = FastutilInterfaceDispatchArtPass()
    private val target = "com.intellij.lang.impl.MarkerOptionalData"
    private val targetInternal = "com/intellij/lang/impl/MarkerOptionalData"
    private val shim = "dev/ide/psi/compat/FastutilDirectCalls"
    private val intSet = "it/unimi/dsi/fastutil/ints/IntSet"
    private val int2Object = "it/unimi/dsi/fastutil/ints/Int2ObjectMap"

    @Test
    fun handlesOnlyThePsiBuilderClasses() {
        assertTrue(pass.handles(target))
        assertTrue(pass.handles("com.intellij.lang.impl.PsiBuilderImpl\$MyTreeStructure"))
        assertFalse(pass.handles("com.intellij.lang.impl.PsiBuilderImpl"))
        assertFalse(pass.handles("it.unimi.dsi.fastutil.ints.IntOpenHashSet"))
    }

    @Test
    fun routesFastutilInterfaceCallsThroughTheShim() {
        val out = runPass(syntheticMarkerOptionalData(includeUnmapped = false))

        val isCollapsed = callsIn(out, "isCollapsed")
        assertEquals(
            listOf(Call(Opcodes.INVOKESTATIC, shim, "intSetContains", "(L$intSet;I)Z")),
            isCollapsed,
        )
        val getDoneError = callsIn(out, "getDoneError")
        assertEquals(
            listOf(Call(Opcodes.INVOKESTATIC, shim, "int2ObjectGet", "(L$int2Object;I)Ljava/lang/Object;")),
            getDoneError,
        )
        // A non-fastutil interface call in the same class is untouched.
        val other = callsIn(out, "other")
        assertEquals(listOf(Call(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I")), other)
    }

    @Test
    fun failsTheBuildOnAnUnmappedFastutilCall() {
        assertFailsWith<IllegalStateException> { runPass(syntheticMarkerOptionalData(includeUnmapped = true)) }
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor(target, writer), 0)
        return writer.toByteArray()
    }

    /** `isCollapsed(int)` and `getDoneError(int)` as core-impl compiles them (getfield + invokeinterface), plus a
     *  non-fastutil interface call, and optionally a fastutil call the pass has no shim for. */
    private fun syntheticMarkerOptionalData(includeUnmapped: Boolean): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_FINAL, targetInternal, null, "java/util/BitSet", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "myCollapsed", "L$intSet;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "myDoneErrors", "L$int2Object;", null, null).visitEnd()

        cw.visitMethod(0, "isCollapsed", "(I)Z", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, targetInternal, "myCollapsed", "L$intSet;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, intSet, "contains", "(I)Z", true)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(0, "getDoneError", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, targetInternal, "myDoneErrors", "L$int2Object;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, int2Object, "get", "(I)Ljava/lang/Object;", true)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(0, "other", "(Ljava/util/Set;)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        if (includeUnmapped) {
            cw.visitMethod(0, "count", "()I", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, targetInternal, "myCollapsed", "L$intSet;")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, intSet, "size", "()I", true)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private data class Call(val opcode: Int, val owner: String, val name: String, val descriptor: String)

    private fun callsIn(bytes: ByteArray, method: String): List<Call> {
        val calls = mutableListOf<Call>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? = if (name != method) null else object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
                ) {
                    calls += Call(opcode, owner, name, descriptor)
                }
            }
        }, 0)
        return calls
    }
}
