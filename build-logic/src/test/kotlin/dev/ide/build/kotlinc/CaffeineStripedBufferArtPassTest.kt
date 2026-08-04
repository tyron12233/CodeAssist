package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CaffeineStripedBufferArtPass] must move caffeine's `StripedBuffer` thread-probe off the JDK-internal
 * `Thread.threadLocalRandomProbe` field: `getProbe`/`advanceProbe` delegate to the [CaffeineThreadProbe] shim,
 * and the `<clinit>` reflection on that field is dropped — while the ART-safe `tableBusy` offset lookup stays.
 * Verified purely in bytecode against a synthetic class shaped like caffeine 2.9.3's `StripedBuffer`.
 */
class CaffeineStripedBufferArtPassTest {

    private val pass = CaffeineStripedBufferArtPass()
    private val target = "com.github.benmanes.caffeine.cache.StripedBuffer"
    private val targetInternal = "com/github/benmanes/caffeine/cache/StripedBuffer"
    private val unsafeAccess = "com/github/benmanes/caffeine/cache/UnsafeAccess"
    private val unsafe = "sun/misc/Unsafe"
    private val shim = "dev/ide/lang/jdt/compat/CaffeineThreadProbe"

    @Test
    fun handlesExactlyStripedBuffer() {
        assertTrue(pass.handles(target))
        assertFalse(pass.handles("com.github.benmanes.caffeine.cache.BoundedBuffer"))
        assertFalse(pass.handles("com.github.benmanes.caffeine.cache.StripedBufferExtra"))
        assertFalse(pass.handles("java.lang.Thread"))
    }

    @Test
    fun redirectsProbeMethodsToShimAndDropsThreadFieldReflection() {
        val out = runPass(syntheticStripedBuffer())

        // The JDK-internal Thread field is no longer reflected anywhere; the shim is now referenced.
        assertFalse(referencesType(out, "threadLocalRandomProbe"), "the Thread.threadLocalRandomProbe reflection must be gone")
        assertTrue(referencesType(out, shim), "the CaffeineThreadProbe shim must be referenced")

        // getProbe now just calls the shim; no Unsafe.getInt on the Thread survives.
        val getProbe = callsIn(out, "getProbe")
        assertTrue(
            getProbe.any { it.opcode == Opcodes.INVOKESTATIC && it.owner == shim && it.name == "getProbe" },
            "getProbe must delegate to CaffeineThreadProbe.getProbe; got $getProbe",
        )
        assertFalse(getProbe.any { it.owner == unsafe }, "no Unsafe call may remain in getProbe; got $getProbe")

        // advanceProbe likewise delegates (the shim mirrors the (I)I descriptor).
        val advanceProbe = callsIn(out, "advanceProbe")
        assertTrue(
            advanceProbe.any { it.opcode == Opcodes.INVOKESTATIC && it.owner == shim && it.name == "advanceProbe" },
            "advanceProbe must delegate to CaffeineThreadProbe.advanceProbe; got $advanceProbe",
        )
        assertFalse(advanceProbe.any { it.owner == unsafe }, "no Unsafe call may remain in advanceProbe; got $advanceProbe")

        // <clinit> keeps exactly the ART-safe tableBusy offset lookup — the probe one (2 → 1) is dropped.
        val clinitOffsets = callsIn(out, "<clinit>")
            .count { it.opcode == Opcodes.INVOKESTATIC && it.owner == unsafeAccess && it.name == "objectFieldOffset" }
        assertEquals(1, clinitOffsets, "only the StripedBuffer.tableBusy offset lookup must remain in <clinit>")
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor(target, writer), 0)
        return writer.toByteArray()
    }

    /** A synthetic class shaped like caffeine 2.9.3's `StripedBuffer`: a `<clinit>` computing `TABLE_BUSY` (its
     *  own field, ART-safe) then `PROBE` (the Thread field, ART-hostile), plus the two Unsafe-on-Thread probe
     *  methods. Only structurally parseable, not necessarily verifiable — enough for the bytecode assertions. */
    private fun syntheticStripedBuffer(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_ABSTRACT, targetInternal, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "TABLE_BUSY", "J", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "PROBE", "J", null, null).visitEnd()

        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            // TABLE_BUSY = UnsafeAccess.objectFieldOffset(StripedBuffer.class, "tableBusy") — must be KEPT.
            visitLdcInsn(Type.getObjectType(targetInternal))
            visitLdcInsn("tableBusy")
            visitMethodInsn(Opcodes.INVOKESTATIC, unsafeAccess, "objectFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false)
            visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, "TABLE_BUSY", "J")
            // PROBE = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe") — must be DROPPED to 0L.
            visitLdcInsn(Type.getObjectType("java/lang/Thread"))
            visitLdcInsn("threadLocalRandomProbe")
            visitMethodInsn(Opcodes.INVOKESTATIC, unsafeAccess, "objectFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false)
            visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, "PROBE", "J")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 0)
            visitEnd()
        }

        // static final int getProbe() { return UNSAFE.getInt(Thread.currentThread(), PROBE); }
        cw.visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "getProbe", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, unsafeAccess, "UNSAFE", "L$unsafe;")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false)
            visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "PROBE", "J")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, unsafe, "getInt", "(Ljava/lang/Object;J)I", false)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(4, 0)
            visitEnd()
        }

        // static final int advanceProbe(int probe) { ...xorshift...; UNSAFE.putInt(currentThread(), PROBE, probe); return probe; }
        cw.visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "advanceProbe", "(I)I", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, unsafeAccess, "UNSAFE", "L$unsafe;")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false)
            visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "PROBE", "J")
            visitVarInsn(Opcodes.ILOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, unsafe, "putInt", "(Ljava/lang/Object;JI)V", false)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private data class Insn(val opcode: Int, val owner: String, val name: String)

    /** The call/field instructions inside [method] of the given class bytes. */
    private fun callsIn(bytes: ByteArray, method: String): List<Insn> {
        val out = ArrayList<Insn>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor? {
                if (n != method) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) { out.add(Insn(op, owner, name)) }
                    override fun visitFieldInsn(op: Int, owner: String, name: String, desc: String) { out.add(Insn(op, owner, name)) }
                }
            }
        }, 0)
        return out
    }

    private fun referencesType(bytes: ByteArray, needleStr: String): Boolean {
        val needle = needleStr.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
