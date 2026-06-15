package dev.ide.build.engine

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [ExitGuard] rewrites `System.exit`/`Runtime.exit` so an in-process run's exit becomes a catchable
 * [ControlledExit] instead of killing the host JVM. Proven end-to-end: generate a class that exits, run
 * the instrumented bytecode, and confirm it threw [ControlledExit] with the right code (rather than
 * actually exiting — which would take this test JVM down with it).
 */
class ExitGuardTest {

    @Test
    fun rewritesSystemExitToControlledExit() {
        val code = runInstrumented(generateExiter("java/lang/System", "exit", static = true, exitCode = 42))
        assertEquals(42, code, "System.exit(42) must surface as ControlledExit(42)")
    }

    @Test
    fun rewritesRuntimeExitToControlledExit() {
        val code = runInstrumented(generateExiter("java/lang/Runtime", "exit", static = false, exitCode = 7))
        assertEquals(7, code, "Runtime.getRuntime().exit(7) must surface as ControlledExit(7)")
    }

    @Test
    fun leavesExitFreeClassesUntouched() {
        val original = generatePrinter()
        val instrumented = ExitGuard.instrument(original)
        assertTrue(original.contentEquals(instrumented), "a class that never calls exit must pass through byte-for-byte")
    }

    /** Define + run the instrumented [classBytes]' static `run()` and return the trapped exit code. */
    private fun runInstrumented(classBytes: ByteArray): Int {
        val instrumented = ExitGuard.instrument(classBytes)
        assertTrue("dev/ide/build/engine/RunGuard" in String(instrumented, Charsets.ISO_8859_1), "exit call must be redirected to RunGuard")
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> =
                if (name == "Exiter") defineClass(name, instrumented, 0, instrumented.size) else super.findClass(name)
        }
        val run = loader.loadClass("Exiter").getDeclaredMethod("run")
        try {
            run.invoke(null)
            return fail("instrumented run() should have thrown ControlledExit, not returned")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is ControlledExit) return cause.code
            return fail("expected ControlledExit, got $cause")
        }
    }

    /** A class `Exiter` whose static `run()` exits via [owner].[method] (System.exit / Runtime.exit). */
    private fun generateExiter(owner: String, method: String, static: Boolean, exitCode: Int): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Exiter", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
        mv.visitCode()
        if (static) {
            mv.visitIntInsn(Opcodes.BIPUSH, exitCode)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, "(I)V", false)
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
            mv.visitIntInsn(Opcodes.BIPUSH, exitCode)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, "(I)V", false)
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A class with no exit call — used to confirm the instrumenter is a no-op for such classes. */
    private fun generatePrinter(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Printer", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
        mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
