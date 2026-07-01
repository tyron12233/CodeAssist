package dev.ide.build.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Thrown by [RunGuard] when instrumented user code calls `System.exit`/`Runtime.exit`/`Runtime.halt`. It is
 * an [Error] (not an [Exception]) so a user `catch (Exception)` won't swallow it; the dex runner catches it
 * to end the *run* with [code] — instead of the real `exit` terminating the whole IDE process (in-process
 * execution on ART, where a `SecurityManager` exit trap is unsupported).
 */
class ControlledExit(val code: Int) : Error("exit($code)")

/**
 * The redirect target [ExitGuard] rewrites process-exit calls to. Each throws [ControlledExit] instead of
 * really exiting. Declared `Unit`-returning so the JVM descriptors match the calls they replace exactly:
 * [exit] is `(I)V` (← `System.exit`), [exitVia] is `(Ljava/lang/Runtime;I)V` (← `Runtime.exit`/`halt`,
 * consuming the receiver left on the stack). `@JvmStatic` so the rewritten `invokestatic` resolves.
 */
object RunGuard {
    @JvmStatic fun exit(code: Int) { throw ControlledExit(code) }
    @JvmStatic fun exitVia(runtime: Runtime?, code: Int) { throw ControlledExit(code) }
}

/**
 * Rewrites `System.exit(int)` → [RunGuard.exit] and `Runtime.exit/halt(int)` → [RunGuard.exitVia] in user
 * bytecode, so a program's exit ends the in-process dex-run (via [ControlledExit]) rather than killing the
 * IDE. Applied by `JavaDexTask` to the run's own class-dir inputs (library jars pass through unchanged).
 * The rewrite preserves stack shape (both replacements consume the same operands), so frames/maxs are
 * untouched. A cheap constant-pool pre-scan skips the (vast majority of) classes that can't call exit.
 */
object ExitGuard {
    private const val GUARD = "dev/ide/build/engine/RunGuard"

    /** Bump when the rewrite changes. Folded into the run-dex cache key so cached instrumented library dex
     *  is invalidated by a guard change (see [RunDexRequest.guardVersion]). */
    const val VERSION = 1

    fun instrument(classBytes: ByteArray): ByteArray {
        if (!mightCallExit(classBytes)) return classBytes
        val reader = ClassReader(classBytes)
        // flags 0: stack shape is preserved, so don't recompute maxs/frames (reuses the reader's pool too).
        val writer = ClassWriter(reader, 0)
        var rewrote = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String?, desc: String?, sig: String?, exceptions: Array<out String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, desc, sig, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(opcode: Int, owner: String?, mName: String?, mDesc: String?, itf: Boolean) {
                        when {
                            opcode == Opcodes.INVOKESTATIC && owner == "java/lang/System" && mName == "exit" && mDesc == "(I)V" -> {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "exit", "(I)V", false); rewrote = true
                            }
                            opcode == Opcodes.INVOKEVIRTUAL && owner == "java/lang/Runtime" && (mName == "exit" || mName == "halt") && mDesc == "(I)V" -> {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "exitVia", "(Ljava/lang/Runtime;I)V", false); rewrote = true
                            }
                            else -> super.visitMethodInsn(opcode, owner, mName, mDesc, itf)
                        }
                    }
                }
            }
        }, 0)
        return if (rewrote) writer.toByteArray() else classBytes
    }

    /** Cheap pre-filter: only classes whose constant pool names System/Runtime + exit/halt can possibly call them. */
    private fun mightCallExit(bytes: ByteArray): Boolean {
        val s = String(bytes, Charsets.ISO_8859_1)
        return ("java/lang/System" in s && "exit" in s) ||
            ("java/lang/Runtime" in s && ("exit" in s || "halt" in s))
    }
}
