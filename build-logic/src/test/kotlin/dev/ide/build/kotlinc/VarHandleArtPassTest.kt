package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VarHandleArtPass] must relocate every `java.lang.invoke.VarHandle` reference (fields, factory calls, and the
 * polymorphic access-mode call sites) to the [VarHandleCompat] shim, leaving no `java/lang/invoke/VarHandle`
 * reference behind, and insert a `checkcast` after an object-array `getVolatile`. Verified purely in bytecode.
 */
class VarHandleArtPassTest {

    private val pass = VarHandleArtPass()
    private val target = "com.intellij.concurrency.ConcurrentHashMap"
    private val targetInternal = "com/intellij/concurrency/ConcurrentHashMap"
    private val nodeType = "com/intellij/concurrency/ConcurrentHashMap\$Node"
    private val varHandle = "java/lang/invoke/VarHandle"
    private val shim = "dev/ide/lang/jdt/compat/VarHandleCompat"

    @Test
    fun handlesTheConcurrencyClassesAndTheirInnerClasses() {
        assertTrue(pass.handles("com.intellij.util.concurrency.AtomicFieldUpdater"))
        assertTrue(pass.handles("com.intellij.util.containers.ConcurrentBitSetImpl"))
        assertTrue(pass.handles(target))
        assertTrue(pass.handles("$target\$Node"))
        assertTrue(pass.handles("$target\$TreeBin"))
        assertTrue(pass.handles("com.intellij.concurrency.ConcurrentIntObjectHashMap"))
        assertTrue(pass.handles("com.intellij.concurrency.ConcurrentLongObjectHashMap"))
        assertFalse(pass.handles("com.intellij.concurrency.ConcurrentHashMapExtra"), "must not prefix-match a sibling")
        assertFalse(pass.handles("java.lang.invoke.VarHandle"))
    }

    @Test
    fun relocatesEveryVarHandleReferenceAndInsertsArrayGetCheckcast() {
        val out = runPass(syntheticClass())
        assertFalse(referencesType(out, varHandle), "no residual java/lang/invoke/VarHandle reference may remain")
        assertTrue(referencesType(out, shim), "the shim must be referenced after relocation")

        val calls = collectCalls(out)
        assertTrue(
            calls.any { it.opcode == Opcodes.INVOKESTATIC && it.owner == shim && it.name == "forField" },
            "findVarHandle must become an invokestatic to VarHandleCompat.forField; got $calls",
        )
        assertTrue(
            calls.any { it.opcode == Opcodes.INVOKESTATIC && it.owner == shim && it.name == "forArray" },
            "arrayElementVarHandle must become VarHandleCompat.forArray; got $calls",
        )
        assertTrue(
            calls.any { it.opcode == Opcodes.INVOKEVIRTUAL && it.owner == shim && it.name == "compareAndSet" },
            "a field compareAndSet must dispatch on the shim; got $calls",
        )
        // The object-array getVolatile returns Object from the shim, so its declared Node element type must be
        // restored with a checkcast right after the call.
        val getIdx = calls.indexOfFirst { it.opcode == Opcodes.INVOKEVIRTUAL && it.owner == shim && it.name == "getVolatile" }
        assertTrue(getIdx >= 0, "an array getVolatile must dispatch on the shim; got $calls")
        assertTrue(
            calls.getOrNull(getIdx + 1).let { it?.opcode == Opcodes.CHECKCAST && it.owner == nodeType },
            "a checkcast to the Node element type must follow the array getVolatile; got $calls",
        )
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor(target, writer), 0)
        return writer.toByteArray()
    }

    /** A synthetic target class using a field VarHandle (int field CAS) and an array VarHandle (Node[] get),
     *  both created the JDK-CHM way. Not necessarily verifiable — only structurally parseable for the assertions. */
    private fun syntheticClass(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_FINAL, targetInternal, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "SIZECTL", "L$varHandle;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "TAB", "L$varHandle;", null, null).visitEnd()

        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            // SIZECTL = privateLookupIn(CHM.class, lookup()).findVarHandle(CHM.class, "sizeCtl", int.class)
            visitLdcInsn(org.objectweb.asm.Type.getObjectType(targetInternal))
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles\$Lookup;", false)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles\$Lookup;)Ljava/lang/invoke/MethodHandles\$Lookup;", false)
            visitLdcInsn(org.objectweb.asm.Type.getObjectType(targetInternal))
            visitLdcInsn("sizeCtl")
            visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles\$Lookup", "findVarHandle", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)L$varHandle;", false)
            visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, "SIZECTL", "L$varHandle;")
            // TAB = arrayElementVarHandle(Node[].class)
            visitLdcInsn(org.objectweb.asm.Type.getType("[L$nodeType;"))
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "arrayElementVarHandle", "(Ljava/lang/Class;)L$varHandle;", false)
            visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, "TAB", "L$varHandle;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 0)
            visitEnd()
        }

        // boolean casSizeCtl(int expected, int update) { return SIZECTL.compareAndSet(this, expected, update); }
        cw.visitMethod(Opcodes.ACC_FINAL, "casSizeCtl", "(II)Z", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "SIZECTL", "L$varHandle;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, varHandle, "compareAndSet", "(L$targetInternal;II)Z", false)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(4, 3)
            visitEnd()
        }

        // Node tabAt(Node[] tab, int i) { return (Node) TAB.getVolatile(tab, i); }
        cw.visitMethod(Opcodes.ACC_FINAL, "tabAt", "([L$nodeType;I)L$nodeType;", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "TAB", "L$varHandle;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, varHandle, "getVolatile", "([L$nodeType;I)L$nodeType;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private data class Insn(val opcode: Int, val owner: String, val name: String)

    private fun collectCalls(bytes: ByteArray): List<Insn> {
        val out = ArrayList<Insn>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) { out.add(Insn(op, owner, name)) }
                    override fun visitTypeInsn(op: Int, type: String) { out.add(Insn(op, type, "")) }
                }
        }, 0)
        return out
    }

    private fun referencesType(bytes: ByteArray, internalName: String): Boolean {
        val needle = internalName.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
