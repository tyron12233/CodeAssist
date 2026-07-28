package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Makes `com.intellij.util.ui.EDT.isCurrentThreadEdt()` return `true` on ART (pretend the current thread IS
 * the Event Dispatch Thread).
 *
 * Two reasons, both from the on-device bytecode (via `KspArtSpikeTest`):
 *  1. **AWT is absent.** The real body returns `currentThread == myEventDispatchThread` when that static field
 *     is set, but falls back to `java.awt.EventQueue.isDispatchThread()` when it is null — and on ART the field
 *     is never initialized (no AWT), so the call throws `NoClassDefFoundError: java.awt.EventQueue`.
 *  2. **Polarity matters.** `ThreadingAssertions.assertEventDispatchThread()` is `if (!isCurrentThreadEdt())
 *     throwThreadAccessException()`, and building that exception calls `getThreadDetails()` — which ALSO touches
 *     `EventQueue`. KSP2's `KotlinSymbolProcessing` runs its PSI-cache drop inside `runWriteAction`, which
 *     asserts EDT. On the desktop this passes because the standalone Analysis API registers its worker thread
 *     as the EDT (so `isCurrentThreadEdt()` is `true` there); returning `true` on ART matches that exactly.
 *     Returning `false` instead makes the assertion throw (observed on device before this fix).
 *
 * Pretending we are the EDT is the correct headless assumption for KSP's single-threaded standalone run: every
 * "must run on the EDT" assertion passes and read/write actions run synchronously on the calling thread. The
 * whole method body is replaced (canonical ASM "replace body" idiom: drive the writer's `MethodVisitor` with
 * the new body, then return `null` so the reader skips the original), so `java.awt.EventQueue` is never
 * referenced from `isCurrentThreadEdt`.
 *
 * Rides the same scope=ALL instrumentation as the other passes, so it reaches `com.intellij.util.ui.EDT` in
 * the merged `:kotlin-compiler-deps` jar wherever it is dexed (the compiler, the AA, and the editor parse-host
 * all share it).
 */
class EdtHeadlessArtPass : ArtPatchPass {

    override val name: String = "edt-headless"

    override fun handles(classFqn: String): Boolean = classFqn.endsWith("com.intellij.util.ui.EDT")

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = Rewriter(next)

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == "isCurrentThreadEdt" && descriptor == "()Z") {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                // Replacement body: `return true` — pretend the calling thread is the EDT (headless run), so
                // the EDT assertions pass and the AWT fallback / getThreadDetails path is never reached.
                mv.visitCode()
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(1, 0)
                mv.visitEnd()
                // Skip the original body (which references java.awt.EventQueue).
                return null
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
    }
}
