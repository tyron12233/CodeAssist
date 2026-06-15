package dev.ide.build.engine

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [SandboxGuard] rewrites sensitive call sites (method calls + constructors) to [Guards] trampolines, which
 * consult the [PermissionBroker]. Proven by generating a `Probe` class that performs each kind of guarded
 * operation, instrumenting it, and running it with a fake broker: a denied category throws SecurityException
 * (the op never happens), an allowed one proceeds, and with no broker it's a transparent pass-through.
 */
class SandboxGuardTest {

    private class FakeBroker(private val allow: Set<GuardCategory>) : PermissionBroker {
        val seen = mutableListOf<Pair<GuardCategory, String>>()
        override fun check(category: GuardCategory, detail: String): Boolean { seen.add(category to detail); return category in allow }
    }

    @AfterTest fun reset() { Guards.broker = null }

    @Test
    fun rewritesAreInPlace() {
        val instrumented = SandboxGuard.instrument(probe())
        val text = String(instrumented, Charsets.ISO_8859_1)
        assertTrue("dev/ide/build/engine/Guards" in text, "guarded calls must be redirected to Guards")
        assertTrue("newFileInputStream" in text, "the FileInputStream constructor must be rewritten to a factory")
        assertTrue("openStream" in text && "classForName" in text, "method calls must be rewritten")
    }

    @Test
    fun deniedReflectionThrows() {
        val probe = define()
        Guards.broker = FakeBroker(emptySet())
        val ex = assertFailsWithCause<SecurityException> { probe.getDeclaredMethod("forName").invoke(null) }
        assertTrue("reflection" in ex.message!!.lowercase(), "message names the category: ${ex.message}")
    }

    @Test
    fun allowedReflectionProceeds() {
        val probe = define()
        val broker = FakeBroker(setOf(GuardCategory.REFLECTION))
        Guards.broker = broker
        val result = probe.getDeclaredMethod("forName").invoke(null)
        assertEquals(String::class.java, result, "allowed Class.forName must return the real class")
        assertEquals(GuardCategory.REFLECTION to "java.lang.String", broker.seen.single())
    }

    @Test
    fun deniedFileReadThrows() {
        val probe = define()
        Guards.broker = FakeBroker(emptySet())
        // The instrumented `new FileInputStream("/no/such/file")` must be blocked BEFORE construction —
        // a SecurityException (guard), not a FileNotFoundException (the file was never opened).
        assertFailsWithCause<SecurityException> { probe.getDeclaredMethod("fileIn").invoke(null) }
    }

    @Test
    fun deniedNetworkAndExecThrow() {
        val probe = define()
        Guards.broker = FakeBroker(emptySet())
        assertFailsWithCause<SecurityException> { probe.getDeclaredMethod("openStream").invoke(null) }
        assertFailsWithCause<SecurityException> { probe.getDeclaredMethod("exec").invoke(null) }
    }

    @Test
    fun noBrokerIsPassThrough() {
        val probe = define()
        Guards.broker = null
        assertEquals(String::class.java, probe.getDeclaredMethod("forName").invoke(null), "with no broker, guards are transparent")
    }

    // --- helpers ---

    private inline fun <reified T : Throwable> assertFailsWithCause(block: () -> Unit): T {
        try {
            block(); fail("expected ${T::class.simpleName}")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is T) return cause
            fail("expected ${T::class.simpleName}, got $cause")
        }
    }

    private fun define(): Class<*> {
        val bytes = SandboxGuard.instrument(probe())
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> =
                if (name == "Probe") defineClass(name, bytes, 0, bytes.size) else super.findClass(name)
        }
        return loader.loadClass("Probe")
    }

    /** A class whose static methods each perform one guarded operation. */
    private fun probe(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)

        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "forName", "()Ljava/lang/Class;", null, null).apply {
            visitCode(); visitLdcInsn("java.lang.String")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "openStream", "()Ljava/lang/Object;", null, null).apply {
            visitCode(); visitTypeInsn(Opcodes.NEW, "java/net/URL"); visitInsn(Opcodes.DUP); visitLdcInsn("http://example.com")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "fileIn", "()Ljava/lang/Object;", null, null).apply {
            visitCode(); visitTypeInsn(Opcodes.NEW, "java/io/FileInputStream"); visitInsn(Opcodes.DUP); visitLdcInsn("/no/such/file")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/FileInputStream", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "exec", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
            visitLdcInsn("/bin/true")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
