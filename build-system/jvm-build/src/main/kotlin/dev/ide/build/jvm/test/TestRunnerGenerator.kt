package dev.ide.build.jvm.test

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.BIPUSH
import org.objectweb.asm.Opcodes.CHECKCAST
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.GOTO
import org.objectweb.asm.Opcodes.IADD
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.ICONST_1
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.IFNONNULL
import org.objectweb.asm.Opcodes.IFNULL
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.Opcodes.INSTANCEOF
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.ISTORE
import org.objectweb.asm.Opcodes.LCONST_0
import org.objectweb.asm.Opcodes.LDIV
import org.objectweb.asm.Opcodes.LLOAD
import org.objectweb.asm.Opcodes.LSTORE
import org.objectweb.asm.Opcodes.LSUB
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.POP2
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_6
import org.objectweb.asm.Type

/**
 * Generates the class a test run executes: a `main` that calls every selected test DIRECTLY, with no reflection.
 *
 * A reflective launcher (JUnit's own) would have to enumerate methods and annotations of interpreted classes inside
 * the bytecode VM, where that reflection is partial. Calling `new FooTest().bar()` is plain bytecode the VM runs like
 * any program, on the desktop and on ART alike, and any method name works (Kotlin's backticked test names
 * included). Per test it runs the before-hooks, the test, the expected-exception check and the after-hooks (always),
 * catches whatever is thrown, and reports through [emit] in the [TestProtocol] format.
 *
 * Emitted at class version 50 so it needs no stack-map frames: computing them would mean loading the user's classes,
 * and the VM reads classes with frames skipped anyway.
 */
object TestRunnerGenerator {

    /** The generated class's internal name; nothing under `dev/` is bridged, so the VM interprets it. */
    const val MAIN_CLASS = "dev/ide/testrun/Main"

    fun generate(classes: List<TestClass>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_6, ACC_PUBLIC or ACC_FINAL, MAIN_CLASS, null, "java/lang/Object", null)
        writeEmit(cw)
        writeDescribe(cw)
        classes.forEachIndexed { ci, c ->
            writeClassMethod(cw, ci, c)
            c.tests.forEachIndexed { ti, t -> writeTestMethod(cw, ci, ti, c, t) }
        }
        writeMain(cw, classes)
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ---- main --------------------------------------------------------------------------------------------

    private fun writeMain(cw: ClassWriter, classes: List<TestClass>) {
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        mv.visitCode()
        // The whole plan first, so a results view can show every test as pending before any of them runs.
        for (c in classes) for (t in c.tests) emit(mv, TestProtocol.QUEUED, c.fqn, t.name)
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ISTORE, 1) // failed
        classes.indices.forEach { ci ->
            mv.visitVarInsn(ILOAD, 1)
            mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "c$ci", "()I", false)
            mv.visitInsn(IADD)
            mv.visitVarInsn(ISTORE, 1)
        }
        emit(mv, TestProtocol.FINISHED, "", "")
        val ok = Label()
        mv.visitVarInsn(ILOAD, 1)
        mv.visitJumpInsn(IFEQ, ok)
        mv.visitInsn(ICONST_1)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false)
        mv.visitLabel(ok)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // ---- one class -------------------------------------------------------------------------------------

    /** `static int cN()`: the class's all-hooks around its tests; returns how many failed. */
    private fun writeClassMethod(cw: ClassWriter, ci: Int, c: TestClass) {
        val mv = cw.visitMethod(ACC_STATIC, "c$ci", "()I", null, null)
        mv.visitCode()
        if (c.skipReason != null) {
            for (t in c.tests) emit(mv, TestProtocol.SKIPPED, c.fqn, t.name, message = c.skipReason)
            mv.visitInsn(ICONST_0)
            mv.visitInsn(IRETURN)
            mv.visitMaxs(0, 0); mv.visitEnd()
            return
        }
        // locals: 0 shared instance, 1 failed count, 2 caught throwable
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 0)
        mv.visitInsn(ICONST_0); mv.visitVarInsn(ISTORE, 1)

        val runTests = Label()
        // Only a class with setup gets the guarded block: an empty try range is not valid bytecode.
        if (c.sharedInstance || c.beforeAll.isNotEmpty()) {
            val setupStart = Label(); val setupEnd = Label(); val setupFailed = Label()
            mv.visitTryCatchBlock(setupStart, setupEnd, setupFailed, "java/lang/Throwable")
            mv.visitLabel(setupStart)
            if (c.sharedInstance) {
                newInstance(mv, c.internalName)
                mv.visitVarInsn(ASTORE, 0)
            }
            for (h in c.beforeAll) callHook(mv, c, h, receiverLocal = 0)
            mv.visitLabel(setupEnd)
            mv.visitJumpInsn(GOTO, runTests)

            // A failing setup fails every test of the class, without running them.
            mv.visitLabel(setupFailed)
            mv.visitVarInsn(ASTORE, 2)
            for (t in c.tests) {
                if (t.skipReason != null) emit(mv, TestProtocol.SKIPPED, c.fqn, t.name, message = t.skipReason)
                else emitWithThrowable(mv, TestProtocol.FAILED, c.fqn, t.name, prefix = "Class setup failed: ", throwableLocal = 2)
            }
            pushInt(mv, c.tests.count { it.skipReason == null })
            mv.visitInsn(IRETURN)
        }

        mv.visitLabel(runTests)
        c.tests.indices.forEach { ti ->
            mv.visitVarInsn(ILOAD, 1)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "t${ci}_$ti", "(Ljava/lang/Object;)I", false)
            mv.visitInsn(IADD)
            mv.visitVarInsn(ISTORE, 1)
        }
        if (c.afterAll.isNotEmpty()) {
            val start = Label(); val end = Label(); val failed = Label(); val done = Label()
            mv.visitTryCatchBlock(start, end, failed, "java/lang/Throwable")
            mv.visitLabel(start)
            for (h in c.afterAll) callHook(mv, c, h, receiverLocal = 0)
            mv.visitLabel(end)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(failed)
            mv.visitVarInsn(ASTORE, 2)
            emitWithThrowable(mv, TestProtocol.CLASS_FAILED, c.fqn, "", prefix = "Class teardown failed: ", throwableLocal = 2)
            mv.visitIincInsn(1, 1)
            mv.visitLabel(done)
        }
        mv.visitVarInsn(ILOAD, 1)
        mv.visitInsn(IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // ---- one test --------------------------------------------------------------------------------------

    /** `static int tN_M(Object shared)`: 0 when the test passed or was skipped, 1 when it failed. */
    private fun writeTestMethod(cw: ClassWriter, ci: Int, ti: Int, c: TestClass, t: TestMethod) {
        val mv = cw.visitMethod(ACC_STATIC, "t${ci}_$ti", "(Ljava/lang/Object;)I", null, null)
        mv.visitCode()
        if (t.skipReason != null) {
            emit(mv, TestProtocol.SKIPPED, c.fqn, t.name, message = t.skipReason)
            mv.visitInsn(ICONST_0); mv.visitInsn(IRETURN)
            mv.visitMaxs(0, 0); mv.visitEnd()
            return
        }
        // locals: 0 shared, 1 failure, 2 instance, 3-4 start nanos, 5 caught
        emit(mv, TestProtocol.STARTED, c.fqn, t.name)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LSTORE, 3)
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 1)
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 2)

        // The instance: the shared one, or a fresh one per test (JUnit's default).
        guarded(mv, failureLocal = 1) {
            if (c.sharedInstance) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitTypeInsn(CHECKCAST, c.internalName)
            } else {
                newInstance(mv, c.internalName)
            }
            mv.visitVarInsn(ASTORE, 2)
        }
        val report = Label()
        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IFNONNULL, report)

        // Before-hooks then the test: the first throw ends both, as in JUnit.
        guarded(mv, failureLocal = 1) {
            for (h in c.beforeEach) callHook(mv, c, h, receiverLocal = 2)
            mv.visitVarInsn(ALOAD, 2)
            mv.visitTypeInsn(CHECKCAST, c.internalName)
            mv.visitMethodInsn(INVOKEVIRTUAL, c.internalName, t.name, t.descriptor, false)
            popReturn(mv, Type.getReturnType(t.descriptor))
        }
        t.expected?.let { expected -> checkExpected(mv, expected) }
        // After-hooks always run; each one's failure counts only when the test had not already failed.
        for (h in c.afterEach) {
            val start = Label(); val end = Label(); val handler = Label(); val done = Label()
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
            mv.visitLabel(start)
            callHook(mv, c, h, receiverLocal = 2)
            mv.visitLabel(end)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(handler)
            mv.visitVarInsn(ASTORE, 5)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitJumpInsn(IFNONNULL, done)
            mv.visitVarInsn(ALOAD, 5)
            mv.visitVarInsn(ASTORE, 1)
            mv.visitLabel(done)
        }

        mv.visitLabel(report)
        val failed = Label()
        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IFNONNULL, failed)
        emitTimed(mv, TestProtocol.PASSED, c.fqn, t.name, startLocal = 3, throwableLocal = null)
        mv.visitInsn(ICONST_0)
        mv.visitInsn(IRETURN)
        mv.visitLabel(failed)
        emitTimed(mv, TestProtocol.FAILED, c.fqn, t.name, startLocal = 3, throwableLocal = 1)
        mv.visitInsn(ICONST_1)
        mv.visitInsn(IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** No throw → fail with "expected X"; a throw that is an X → pass; any other throw stays the failure. */
    private fun checkExpected(mv: MethodVisitor, expected: String) {
        val threw = Label(); val done = Label()
        mv.visitVarInsn(ALOAD, 1)
        mv.visitJumpInsn(IFNONNULL, threw)
        mv.visitTypeInsn(NEW, "java/lang/AssertionError")
        mv.visitInsn(DUP)
        mv.visitLdcInsn("Expected exception: " + expected.replace('/', '.'))
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false)
        mv.visitVarInsn(ASTORE, 1)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(threw)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitTypeInsn(INSTANCEOF, expected)
        mv.visitJumpInsn(IFEQ, done)
        mv.visitInsn(ACONST_NULL)
        mv.visitVarInsn(ASTORE, 1)
        mv.visitLabel(done)
    }

    /** Runs [body]; a throw from it is stored in [failureLocal] and execution continues after it. */
    private inline fun guarded(mv: MethodVisitor, failureLocal: Int, body: () -> Unit) {
        val start = Label(); val end = Label(); val handler = Label(); val done = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
        mv.visitLabel(start)
        body()
        mv.visitLabel(end)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(handler)
        mv.visitVarInsn(ASTORE, failureLocal)
        mv.visitLabel(done)
    }

    /** A static hook is called on its declaring class; an instance hook on the instance in [receiverLocal]. */
    private fun callHook(mv: MethodVisitor, c: TestClass, h: TestHook, receiverLocal: Int) {
        if (h.isStatic) {
            mv.visitMethodInsn(INVOKESTATIC, h.owner, h.name, h.descriptor, false)
        } else {
            mv.visitVarInsn(ALOAD, receiverLocal)
            mv.visitTypeInsn(CHECKCAST, c.internalName)
            mv.visitMethodInsn(INVOKEVIRTUAL, c.internalName, h.name, h.descriptor, false)
        }
        popReturn(mv, Type.getReturnType(h.descriptor))
    }

    private fun newInstance(mv: MethodVisitor, internalName: String) {
        mv.visitTypeInsn(NEW, internalName)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", "()V", false)
    }

    private fun popReturn(mv: MethodVisitor, type: Type) {
        when (type.size) {
            1 -> mv.visitInsn(POP)
            2 -> mv.visitInsn(POP2)
        }
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        if (value in -128..127) mv.visitIntInsn(BIPUSH, value) else mv.visitLdcInsn(value)
    }

    // ---- reporting -------------------------------------------------------------------------------------

    /** `emit(event, cls, method, 0L, message)` with constant arguments. */
    private fun emit(mv: MethodVisitor, event: String, cls: String, method: String, message: String = "") {
        mv.visitLdcInsn(event)
        mv.visitLdcInsn(cls)
        mv.visitLdcInsn(method)
        mv.visitInsn(LCONST_0)
        mv.visitLdcInsn(message)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "emit", EMIT_DESC, false)
    }

    /** `emit(event, cls, method, 0L, prefix + describe(t))` for the throwable in [throwableLocal]. */
    private fun emitWithThrowable(mv: MethodVisitor, event: String, cls: String, method: String, prefix: String, throwableLocal: Int) {
        mv.visitLdcInsn(event)
        mv.visitLdcInsn(cls)
        mv.visitLdcInsn(method)
        mv.visitInsn(LCONST_0)
        concatDescribe(mv, prefix, throwableLocal)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "emit", EMIT_DESC, false)
    }

    /** `emit(event, cls, method, elapsedMillis, describe(t) or "")`. */
    private fun emitTimed(mv: MethodVisitor, event: String, cls: String, method: String, startLocal: Int, throwableLocal: Int?) {
        mv.visitLdcInsn(event)
        mv.visitLdcInsn(cls)
        mv.visitLdcInsn(method)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LLOAD, startLocal)
        mv.visitInsn(LSUB)
        mv.visitLdcInsn(1_000_000L)
        mv.visitInsn(LDIV)
        if (throwableLocal == null) mv.visitLdcInsn("") else concatDescribe(mv, "", throwableLocal)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "emit", EMIT_DESC, false)
    }

    private fun concatDescribe(mv: MethodVisitor, prefix: String, throwableLocal: Int) {
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitLdcInsn(prefix)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitVarInsn(ALOAD, throwableLocal)
        mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable")
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "describe", "(Ljava/lang/Throwable;)Ljava/lang/String;", false)
        append(mv, "Ljava/lang/String;")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    /**
     * `static void emit(String event, String cls, String method, long ms, String message)`: prints one
     * [TestProtocol] record. Every variable field is length-prefixed, so a class, method or message may hold any
     * character, newlines included, and the record still parses.
     */
    private fun writeEmit(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "emit", EMIT_DESC, null, null)
        mv.visitCode()
        // locals: 0 event, 1 cls, 2 method, 3-4 ms, 5 message
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitLdcInsn(TestProtocol.MARK)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitVarInsn(ALOAD, 0); append(mv, "Ljava/lang/String;")
        appendField(mv, 1)
        appendField(mv, 2)
        mv.visitLdcInsn(" "); append(mv, "Ljava/lang/String;")
        mv.visitVarInsn(LLOAD, 3); append(mv, "J")
        appendField(mv, 5)
        mv.visitLdcInsn("\n"); append(mv, "Ljava/lang/String;")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitVarInsn(ASTORE, 6)
        mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
        mv.visitInsn(DUP)
        mv.visitVarInsn(ALOAD, 6)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "flush", "()V", false)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Appends `" " + s.length() + ":" + s` for the string in [local]. */
    private fun appendField(mv: MethodVisitor, local: Int) {
        mv.visitLdcInsn(" "); append(mv, "Ljava/lang/String;")
        mv.visitVarInsn(ALOAD, local)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
        append(mv, "I")
        mv.visitLdcInsn(":"); append(mv, "Ljava/lang/String;")
        mv.visitVarInsn(ALOAD, local); append(mv, "Ljava/lang/String;")
    }

    /**
     * `static String describe(Throwable t)`: `ClassName: message`. Not `t.toString()`: for an exception class the
     * program defines, that names the VM's generated peer class rather than the program's own.
     */
    private fun writeDescribe(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "describe", "(Ljava/lang/Throwable;)Ljava/lang/String;", null, null)
        mv.visitCode()
        val noMessage = Label()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false)
        mv.visitVarInsn(ASTORE, 1)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false)
        mv.visitVarInsn(ASTORE, 2)
        mv.visitVarInsn(ALOAD, 2)
        mv.visitJumpInsn(IFNULL, noMessage)
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitLdcInsn(": "); append(mv, "Ljava/lang/String;")
        mv.visitVarInsn(ALOAD, 2); append(mv, "Ljava/lang/String;")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(ARETURN)
        mv.visitLabel(noMessage)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun append(mv: MethodVisitor, argDesc: String) {
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "($argDesc)Ljava/lang/StringBuilder;", false)
    }

    private const val EMIT_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V"
}

/**
 * The record format a test run prints on stdout, between the program's own output: [MARK], the event, then
 * `<len>:<class>`, `<len>:<method>`, the elapsed milliseconds and `<len>:<message>`, space-separated, ending in a
 * newline. The host splits these out of the console text (`TestOutputDemux` in ide-ui-api, which spells the same
 * format; a round-trip test keeps the two in step).
 */
object TestProtocol {
    /** Two control characters around a name: nothing a program prints by accident starts a record. */
    const val MARK = "\u0001ca-test\u0002"
    const val QUEUED = "QUEUED"
    const val STARTED = "STARTED"
    const val PASSED = "PASSED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val CLASS_FAILED = "CLASS_FAILED"
    const val FINISHED = "FINISHED"

    /** One record, exactly as the generated runner's `emit` prints it. */
    fun record(event: String, cls: String = "", method: String = "", ms: Long = 0, message: String = ""): String =
        "$MARK$event ${cls.length}:$cls ${method.length}:$method $ms ${message.length}:$message\n"
}
