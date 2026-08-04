package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Makes caffeine's `com.github.benmanes.caffeine.cache.StripedBuffer` ART-safe by moving its thread-probe off
 * the JDK-internal `java.lang.Thread.threadLocalRandomProbe` field and onto the [CaffeineThreadProbe] shim.
 *
 * `StripedBuffer` (a copy of `Striped64`) picks a buffer stripe from a per-thread probe that it reads/writes by
 * reflecting `Thread.threadLocalRandomProbe` via `Unsafe`: `<clinit>` computes its field offset
 * (`UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe")` → `PROBE`), and `getProbe()` /
 * `advanceProbe(int)` do `Unsafe.getInt`/`putInt(Thread.currentThread(), PROBE)`. That field is a non-SDK
 * member on Android; on a strict device the offset lookup fails, `StripedBuffer.<clinit>` throws, and every
 * BOUNDED caffeine cache then dies with `NoClassDefFoundError: com.github.benmanes.caffeine.cache.BoundedBuffer`
 * (`BoundedBuffer extends StripedBuffer`). That is the on-device KSP crash: KSP2's standalone Analysis API
 * (`KotlinStandaloneJvmDependenciesIndex`) is the first code to create a `maximumSize` cache, so it is the first
 * to touch the probe path — the editor's Kotlin backend never builds a bounded cache, which is why the editor
 * runs on device but KSP did not. (It never surfaced on the lenient API-37 emulator; only on a strict device.)
 *
 * This pass rewrites only `StripedBuffer`:
 *  - `getProbe()` and `advanceProbe(int)` bodies are replaced with a single delegating call to the
 *    [CaffeineThreadProbe] shim (a `ThreadLocal`-backed probe with the identical contract), so no `Unsafe`
 *    access on `Thread` runs;
 *  - the `<clinit>` `PROBE = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe")` trio is
 *    dropped to a constant `0L` (PROBE is dead once the probe methods no longer read it), so the JDK-internal
 *    `Thread` field is never reflected. `StripedBuffer`'s OWN field offset (`tableBusy`) and its runtime CAS are
 *    left untouched — those are ART-safe (a non-hidden field + classic `Unsafe.compareAndSwapInt`).
 *
 * Like the other passes it rides the `dev.ide.kotlinc-art` AGP instrumentation (scope = ALL), which reaches the
 * separate caffeine jar; desktop keeps the real `StripedBuffer`.
 */
class CaffeineStripedBufferArtPass : ArtPatchPass {

    override val name: String = "caffeine-thread-probe"

    override fun handles(classFqn: String): Boolean = classFqn == TARGET

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = Rewriter(next)

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor? {
            // Replace the probe accessors outright: emit a fresh body that delegates to the shim, then return
            // null so ASM skips (drops) the original method's Unsafe-on-Thread body.
            if (name == "getProbe" && descriptor == "()I") {
                emitDelegator(access, name, descriptor, signature, exceptions, loadArg = false)
                return null
            }
            if (name == "advanceProbe" && descriptor == "(I)I") {
                emitDelegator(access, name, descriptor, signature, exceptions, loadArg = true)
                return null
            }
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (name == "<clinit>") ClinitRewriter(mv) else mv
        }

        /** Write a `{ [iload 0;] invokestatic CaffeineThreadProbe.<name>; ireturn }` method to the downstream
         *  writer (the shim method mirrors the caffeine method's descriptor). */
        private fun emitDelegator(
            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            loadArg: Boolean,
        ) {
            val mv = cv.visitMethod(access, name, descriptor, signature, exceptions) ?: return
            mv.visitCode()
            if (loadArg) mv.visitVarInsn(Opcodes.ILOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, name, descriptor, false)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(1, if (loadArg) 1 else 0)
            mv.visitEnd()
        }
    }

    /**
     * Rewrites `<clinit>`, dropping the consecutive `ldc Thread.class ; ldc "threadLocalRandomProbe" ;
     * invokestatic UnsafeAccess.objectFieldOffset(Class,String)J` trio to a bare `lconst_0` (so `PROBE` becomes
     * `0L`, never reflecting the JDK-internal field). Everything else — including the ART-safe
     * `objectFieldOffset(StripedBuffer.class, "tableBusy")` — passes through unchanged. Implemented as a small
     * peephole that buffers the two `ldc`s and cancels them when the `objectFieldOffset` call follows; any other
     * instruction flushes the buffered `ldc`s back out, so a stray `Thread.class` / string load is preserved.
     */
    private class ClinitRewriter(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
        private var heldThreadClass = false
        private var heldProbeString = false

        private fun flush() {
            if (heldThreadClass) super.visitLdcInsn(Type.getObjectType(THREAD))
            if (heldProbeString) super.visitLdcInsn(PROBE_FIELD)
            heldThreadClass = false
            heldProbeString = false
        }

        override fun visitLdcInsn(value: Any?) {
            if (!heldThreadClass && value is Type && value.sort == Type.OBJECT && value.internalName == THREAD) {
                heldThreadClass = true
                return
            }
            if (heldThreadClass && !heldProbeString && value == PROBE_FIELD) {
                heldProbeString = true
                return
            }
            flush()
            super.visitLdcInsn(value)
        }

        override fun visitMethodInsn(
            opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean,
        ) {
            if (heldThreadClass && heldProbeString && opcode == Opcodes.INVOKESTATIC &&
                owner == UNSAFE_ACCESS && name == "objectFieldOffset" && descriptor == OFFSET_DESC
            ) {
                heldThreadClass = false
                heldProbeString = false
                super.visitInsn(Opcodes.LCONST_0)
                return
            }
            flush()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInsn(opcode: Int) { flush(); super.visitInsn(opcode) }
        override fun visitIntInsn(opcode: Int, operand: Int) { flush(); super.visitIntInsn(opcode, operand) }
        override fun visitVarInsn(opcode: Int, varIndex: Int) { flush(); super.visitVarInsn(opcode, varIndex) }
        override fun visitTypeInsn(opcode: Int, type: String?) { flush(); super.visitTypeInsn(opcode, type) }
        override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
            flush(); super.visitFieldInsn(opcode, owner, name, descriptor)
        }
        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label?) { flush(); super.visitJumpInsn(opcode, label) }
        override fun visitLabel(label: org.objectweb.asm.Label?) { flush(); super.visitLabel(label) }
        override fun visitLineNumber(line: Int, start: org.objectweb.asm.Label?) { flush(); super.visitLineNumber(line, start) }
        override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
            flush(); super.visitFrame(type, numLocal, local, numStack, stack)
        }
        override fun visitMaxs(maxStack: Int, maxLocals: Int) { flush(); super.visitMaxs(maxStack, maxLocals) }
    }

    private companion object {
        const val TARGET = "com.github.benmanes.caffeine.cache.StripedBuffer"
        const val SHIM = "dev/ide/lang/jdt/compat/CaffeineThreadProbe"
        const val THREAD = "java/lang/Thread"
        const val PROBE_FIELD = "threadLocalRandomProbe"
        const val UNSAFE_ACCESS = "com/github/benmanes/caffeine/cache/UnsafeAccess"
        const val OFFSET_DESC = "(Ljava/lang/Class;Ljava/lang/String;)J"
    }
}
