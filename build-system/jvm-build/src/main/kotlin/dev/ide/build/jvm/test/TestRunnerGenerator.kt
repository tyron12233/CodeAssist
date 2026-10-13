package dev.ide.build.jvm.test

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.AALOAD
import org.objectweb.asm.Opcodes.AASTORE
import org.objectweb.asm.Opcodes.ANEWARRAY
import org.objectweb.asm.Opcodes.ARRAYLENGTH
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.IF_ICMPEQ
import org.objectweb.asm.Opcodes.IF_ICMPGE
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.PUTFIELD
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

    private const val TEST_DESC = "(Ljava/lang/Object;[Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)I"

    fun generate(classes: List<TestClass>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_6, ACC_PUBLIC or ACC_FINAL, MAIN_CLASS, null, "java/lang/Object", null)
        writeEmit(cw)
        writeDescribe(cw)
        writeIter(cw)
        writeArgs(cw)
        if (classes.any { c -> c.tests.any { it.factory != null } }) writeJupiterArgs(cw)
        writeJoin(cw)
        classes.forEachIndexed { ci, c ->
            writeClassMethod(cw, ci, c)
            c.tests.forEachIndexed { ti, t -> writeTestMethod(cw, ci, ti, c, t) }
        }
        writeMain(cw, classes)
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** The name a fixed invocation is reported under: `add[2] 1, 2`, as JUnit numbers and shows it. */
    fun invocationName(t: TestMethod, index: Int, inv: Invocation): String = "${t.name}[$index] ${inv.display}"

    private fun included(t: TestMethod, index: Int) = t.only == null || index in t.only

    // ---- main --------------------------------------------------------------------------------------------

    private fun writeMain(cw: ClassWriter, classes: List<TestClass>) {
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        mv.visitCode()
        // The whole plan first, so a results view can show every test as pending before any of them runs. Runs a
        // source produces while the tests run are listed as they start.
        for (c in classes) for (t in c.tests) {
            when {
                c.classParams != null && c.skipReason == null && t.skipReason == null -> {}
                t.invocations != null && t.skipReason == null && c.skipReason == null ->
                    t.invocations.forEachIndexed { i, inv -> if (included(t, i + 1)) emit(mv, TestProtocol.QUEUED, c.fqn, invocationName(t, i + 1, inv)) }
                t.factory != null && t.skipReason == null && c.skipReason == null -> {}
                else -> emit(mv, TestProtocol.QUEUED, c.fqn, t.name)
            }
        }
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

    /**
     * `static int cN()`: the class's all-hooks around its tests; returns how many failed.
     * Locals: 0 shared instance, 1 failed count, 2 caught throwable, 3 iterator, 4 index, 5 arguments, 6 name.
     */
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
            for (h in c.beforeAll) callHook(mv, c.internalName, h, receiverLocal = 0)
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
        if (c.classParams != null) runParameterSets(mv, ci, c, c.classParams)
        else c.tests.forEachIndexed { ti, t -> runTest(mv, ci, ti, c, t) }

        if (c.afterAll.isNotEmpty()) {
            val start = Label(); val end = Label(); val failed = Label(); val done = Label()
            mv.visitTryCatchBlock(start, end, failed, "java/lang/Throwable")
            mv.visitLabel(start)
            for (h in c.afterAll) callHook(mv, c.internalName, h, receiverLocal = 0)
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

    /** `failed += tN_M(shared, args, ctorArgs, name)` with the arguments already on the stack. */
    private fun callTest(mv: MethodVisitor, ci: Int, ti: Int) {
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "t${ci}_$ti", TEST_DESC, false)
        mv.visitVarInsn(ILOAD, 1)
        mv.visitInsn(IADD)
        mv.visitVarInsn(ISTORE, 1)
    }

    /** One test of a plain class: once, once per fixed invocation, then once per item its source returns. */
    private fun runTest(mv: MethodVisitor, ci: Int, ti: Int, c: TestClass, t: TestMethod) {
        if (!t.parameterized || t.skipReason != null) {
            mv.visitVarInsn(ALOAD, 0); mv.visitInsn(ACONST_NULL); mv.visitInsn(ACONST_NULL); mv.visitLdcInsn(t.name)
            callTest(mv, ci, ti)
            return
        }
        val params = Type.getArgumentTypes(t.descriptor)
        t.invocations.orEmpty().forEachIndexed { i, inv ->
            if (!included(t, i + 1)) return@forEachIndexed
            mv.visitVarInsn(ALOAD, 0)
            pushArgs(mv, inv.args, params)
            mv.visitInsn(ACONST_NULL)
            mv.visitLdcInsn(invocationName(t, i + 1, inv))
            callTest(mv, ci, ti)
        }
        val factory = t.factory ?: return
        // A source that throws fails the test under its own name; nothing of it runs.
        val loop = Label(); val next = Label(); val done = Label()
        callFactory(mv, factory, t.name, c.fqn, failedOut = done)
        pushInt(mv, t.invocations?.size ?: 0)
        mv.visitVarInsn(ISTORE, 4)
        mv.visitLabel(loop)
        mv.visitVarInsn(ALOAD, 3)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
        mv.visitJumpInsn(IFEQ, done)
        mv.visitIincInsn(4, 1)
        mv.visitVarInsn(ALOAD, 3)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
        pushInt(mv, params.size)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "argsJ", "(Ljava/lang/Object;I)[Ljava/lang/Object;", false)
        mv.visitVarInsn(ASTORE, 5)
        skipUnless(mv, t, indexLocal = 4, skip = next)
        // name = t.name + "[" + index + "] " + join(args)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ALOAD, 5)
        mv.visitInsn(ACONST_NULL)
        newBuilder(mv, t.name + "[")
        mv.visitVarInsn(ILOAD, 4); append(mv, "I")
        mv.visitLdcInsn("] "); append(mv, "Ljava/lang/String;")
        mv.visitVarInsn(ALOAD, 5)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "join", "([Ljava/lang/Object;)Ljava/lang/String;", false)
        append(mv, "Ljava/lang/String;")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        callTest(mv, ci, ti)
        mv.visitLabel(next)
        mv.visitJumpInsn(GOTO, loop)
        mv.visitLabel(done)
    }

    /** JUnit 4 `Parameterized`: every test once per set the `@Parameters` method returns, named `test[0]`, `test[1]`. */
    private fun runParameterSets(mv: MethodVisitor, ci: Int, c: TestClass, factory: FactoryRef) {
        c.tests.forEachIndexed { ti, t ->
            if (t.skipReason != null) {
                mv.visitVarInsn(ALOAD, 0); mv.visitInsn(ACONST_NULL); mv.visitInsn(ACONST_NULL); mv.visitLdcInsn(t.name)
                callTest(mv, ci, ti)
            }
        }
        val runnable = c.tests.withIndex().filter { it.value.skipReason == null }
        if (runnable.isEmpty()) return
        val loop = Label(); val done = Label()
        callFactory(mv, factory, null, c.fqn, failedOut = done, failNames = runnable.map { it.value.name })
        pushInt(mv, -1)
        mv.visitVarInsn(ISTORE, 4)
        mv.visitLabel(loop)
        mv.visitVarInsn(ALOAD, 3)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
        mv.visitJumpInsn(IFEQ, done)
        mv.visitIincInsn(4, 1)
        mv.visitVarInsn(ALOAD, 3)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
        pushInt(mv, -1)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "args", "(Ljava/lang/Object;I)[Ljava/lang/Object;", false)
        mv.visitVarInsn(ASTORE, 5)
        for ((ti, t) in runnable) {
            val next = Label()
            skipUnless(mv, t, indexLocal = 4, skip = next)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitInsn(ACONST_NULL)
            mv.visitVarInsn(ALOAD, 5)
            newBuilder(mv, t.name + "[")
            mv.visitVarInsn(ILOAD, 4); append(mv, "I")
            mv.visitLdcInsn("]"); append(mv, "Ljava/lang/String;")
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            callTest(mv, ci, ti)
            mv.visitLabel(next)
        }
        mv.visitJumpInsn(GOTO, loop)
        mv.visitLabel(done)
    }

    /**
     * `iterator = iter(Factory.call())` into local 3. When it throws, report each of [failNames] (or [testName])
     * failed with the cause, count them, and jump to [failedOut].
     */
    private fun callFactory(
        mv: MethodVisitor, factory: FactoryRef, testName: String?, cls: String, failedOut: Label,
        failNames: List<String> = listOfNotNull(testName),
    ) {
        val start = Label(); val end = Label(); val handler = Label(); val ok = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
        mv.visitLabel(start)
        mv.visitMethodInsn(INVOKESTATIC, factory.owner, factory.name, factory.descriptor, false)
        val ret = Type.getReturnType(factory.descriptor)
        if (ret.sort != Type.OBJECT && ret.sort != Type.ARRAY) popReturn(mv, ret).also { mv.visitInsn(ACONST_NULL) }
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "iter", "(Ljava/lang/Object;)Ljava/util/Iterator;", false)
        mv.visitVarInsn(ASTORE, 3)
        mv.visitLabel(end)
        mv.visitJumpInsn(GOTO, ok)
        mv.visitLabel(handler)
        mv.visitVarInsn(ASTORE, 2)
        for (n in failNames) {
            emitWithThrowable(mv, TestProtocol.FAILED, cls, n, prefix = "Parameter source ${factory.name}() failed: ", throwableLocal = 2)
            mv.visitIincInsn(1, 1)
        }
        mv.visitJumpInsn(GOTO, failedOut)
        mv.visitLabel(ok)
    }

    /** Jump to [skip] when [t] runs only some invocations and the one numbered in [indexLocal] is not among them. */
    private fun skipUnless(mv: MethodVisitor, t: TestMethod, indexLocal: Int, skip: Label) {
        val only = t.only ?: return
        val run = Label()
        for (k in only) {
            mv.visitVarInsn(ILOAD, indexLocal)
            pushInt(mv, k)
            mv.visitJumpInsn(IF_ICMPEQ, run)
        }
        mv.visitJumpInsn(GOTO, skip)
        mv.visitLabel(run)
    }

    /** `new Object[] { … }` holding [args], each boxed as its parameter type expects. */
    private fun pushArgs(mv: MethodVisitor, args: List<ArgValue>, params: Array<Type>) {
        pushInt(mv, args.size)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        args.forEachIndexed { i, a ->
            mv.visitInsn(DUP)
            pushInt(mv, i)
            pushArg(mv, a, params.getOrNull(i))
            mv.visitInsn(AASTORE)
        }
    }

    private fun pushArg(mv: MethodVisitor, a: ArgValue, param: Type?) {
        when (a) {
            is ArgValue.EnumConst -> mv.visitFieldInsn(GETSTATIC, a.owner, a.name, "L${a.owner};")
            is ArgValue.FromText -> {
                if (a.name == "<init>") {
                    mv.visitTypeInsn(NEW, a.owner); mv.visitInsn(DUP); mv.visitLdcInsn(a.text)
                    mv.visitMethodInsn(INVOKESPECIAL, a.owner, "<init>", a.descriptor, false)
                } else {
                    mv.visitLdcInsn(a.text)
                    mv.visitMethodInsn(INVOKESTATIC, a.owner, a.name, a.descriptor, false)
                }
            }
            is ArgValue.Const -> when (val v = a.value) {
                null -> mv.visitInsn(ACONST_NULL)
                is String -> mv.visitLdcInsn(v)
                is Type -> mv.visitLdcInsn(v)
                is Int -> box(mv, v, "I", "java/lang/Integer")
                is Long -> box(mv, v, "J", "java/lang/Long")
                is Float -> box(mv, v, "F", "java/lang/Float")
                is Double -> box(mv, v, "D", "java/lang/Double")
                is Short -> { pushInt(mv, v.toInt()); valueOf(mv, "S", "java/lang/Short") }
                is Byte -> { pushInt(mv, v.toInt()); valueOf(mv, "B", "java/lang/Byte") }
                is Char -> { pushInt(mv, v.code); valueOf(mv, "C", "java/lang/Character") }
                is Boolean -> { mv.visitInsn(if (v) ICONST_1 else ICONST_0); valueOf(mv, "Z", "java/lang/Boolean") }
                else -> mv.visitLdcInsn(v.toString())
            }
        }
    }

    private fun box(mv: MethodVisitor, v: Any, prim: String, box: String) {
        mv.visitLdcInsn(v)
        valueOf(mv, prim, box)
    }

    private fun valueOf(mv: MethodVisitor, prim: String, box: String) =
        mv.visitMethodInsn(INVOKESTATIC, box, "valueOf", "($prim)L$box;", false)

    /** Push element [index] of the `Object[]` in [arrayLocal] as a value of [type]: unboxed or cast. */
    private fun unpack(mv: MethodVisitor, arrayLocal: Int, index: Int, type: Type) {
        mv.visitVarInsn(ALOAD, arrayLocal)
        pushInt(mv, index)
        mv.visitInsn(AALOAD)
        val number = { name: String, desc: String ->
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number")
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", name, "()$desc", false)
        }
        when (type.sort) {
            Type.INT -> number("intValue", "I")
            Type.LONG -> number("longValue", "J")
            Type.SHORT -> number("shortValue", "S")
            Type.BYTE -> number("byteValue", "B")
            Type.FLOAT -> number("floatValue", "F")
            Type.DOUBLE -> number("doubleValue", "D")
            Type.BOOLEAN -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
            }
            Type.CHAR -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Character")
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false)
            }
            else -> if (type.internalName != "java/lang/Object") mv.visitTypeInsn(CHECKCAST, type.internalName)
        }
    }

    // ---- one test --------------------------------------------------------------------------------------

    /**
     * `static int tN_M(Object shared, Object[] args, Object[] ctorArgs, String name)`: one run of a test, reported
     * under [name]; 0 when it passed or was skipped, 1 when it failed. [args] are a parameterized run's arguments,
     * ctorArgs a JUnit 4 `Parameterized` set for the instance.
     * Locals: 0-3 the parameters, 4 failure, 5 instance, 6-7 start nanos, 8 caught, 9.. enclosing instances.
     */
    private fun writeTestMethod(cw: ClassWriter, ci: Int, ti: Int, c: TestClass, t: TestMethod) {
        val mv = cw.visitMethod(ACC_STATIC, "t${ci}_$ti", TEST_DESC, null, null)
        mv.visitCode()
        if (t.skipReason != null) {
            emitNamed(mv, TestProtocol.SKIPPED, c.fqn, nameLocal = 3, message = t.skipReason)
            mv.visitInsn(ICONST_0); mv.visitInsn(IRETURN)
            mv.visitMaxs(0, 0); mv.visitEnd()
            return
        }
        emitNamed(mv, TestProtocol.STARTED, c.fqn, nameLocal = 3)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LSTORE, 6)
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 4)
        mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, 5)
        val outerLocal = { level: Int -> 9 + level }

        // The instance: the shared one, or a fresh one per test (JUnit's default), inside its enclosing instances.
        guarded(mv, failureLocal = 4) {
            when {
                c.sharedInstance -> { mv.visitVarInsn(ALOAD, 0); mv.visitTypeInsn(CHECKCAST, c.internalName) }
                c.outer.isNotEmpty() -> {
                    c.outer.forEachIndexed { level, o ->
                        mv.visitTypeInsn(NEW, o.internalName)
                        mv.visitInsn(DUP)
                        if (level == 0) {
                            mv.visitMethodInsn(INVOKESPECIAL, o.internalName, "<init>", "()V", false)
                        } else {
                            mv.visitVarInsn(ALOAD, outerLocal(level - 1))
                            mv.visitMethodInsn(INVOKESPECIAL, o.internalName, "<init>", "(L${c.outer[level - 1].internalName};)V", false)
                        }
                        mv.visitVarInsn(ASTORE, outerLocal(level))
                    }
                    mv.visitTypeInsn(NEW, c.internalName)
                    mv.visitInsn(DUP)
                    mv.visitVarInsn(ALOAD, outerLocal(c.outer.lastIndex))
                    mv.visitMethodInsn(INVOKESPECIAL, c.internalName, "<init>", "(L${c.outer.last().internalName};)V", false)
                }
                c.classParams != null && c.paramFields.isEmpty() && c.paramConstructor != null -> {
                    mv.visitTypeInsn(NEW, c.internalName)
                    mv.visitInsn(DUP)
                    Type.getArgumentTypes(c.paramConstructor).forEachIndexed { i, p -> unpack(mv, 2, i, p) }
                    mv.visitMethodInsn(INVOKESPECIAL, c.internalName, "<init>", c.paramConstructor, false)
                }
                else -> {
                    newInstance(mv, c.internalName)
                    for (f in c.paramFields) {
                        mv.visitInsn(DUP)
                        unpack(mv, 2, f.index, Type.getType(f.descriptor))
                        mv.visitFieldInsn(PUTFIELD, f.owner, f.name, f.descriptor)
                    }
                }
            }
            mv.visitVarInsn(ASTORE, 5)
        }
        val report = Label()
        mv.visitVarInsn(ALOAD, 4)
        mv.visitJumpInsn(IFNONNULL, report)

        // Before-hooks (the enclosing classes' first) then the test: the first throw ends both, as in JUnit.
        guarded(mv, failureLocal = 4) {
            c.outer.forEachIndexed { level, o -> for (h in o.beforeEach) callHook(mv, o.internalName, h, outerLocal(level)) }
            for (h in c.beforeEach) callHook(mv, c.internalName, h, receiverLocal = 5)
            mv.visitVarInsn(ALOAD, 5)
            mv.visitTypeInsn(CHECKCAST, c.internalName)
            if (t.parameterized) Type.getArgumentTypes(t.descriptor).forEachIndexed { i, p -> unpack(mv, 1, i, p) }
            mv.visitMethodInsn(INVOKEVIRTUAL, c.internalName, t.name, t.descriptor, false)
            popReturn(mv, Type.getReturnType(t.descriptor))
        }
        t.expected?.let { expected -> checkExpected(mv, expected) }
        // After-hooks always run, the class's own then its enclosing classes'; each one's failure counts only
        // when the test had not already failed.
        val after = c.afterEach.map { c.internalName to (it to 5) } +
            c.outer.indices.reversed().flatMap { level -> c.outer[level].afterEach.map { c.outer[level].internalName to (it to outerLocal(level)) } }
        for ((cls, hookAndReceiver) in after) {
            val (h, receiver) = hookAndReceiver
            val start = Label(); val end = Label(); val handler = Label(); val done = Label()
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
            mv.visitLabel(start)
            callHook(mv, cls, h, receiver)
            mv.visitLabel(end)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(handler)
            mv.visitVarInsn(ASTORE, 8)
            mv.visitVarInsn(ALOAD, 4)
            mv.visitJumpInsn(IFNONNULL, done)
            mv.visitVarInsn(ALOAD, 8)
            mv.visitVarInsn(ASTORE, 4)
            mv.visitLabel(done)
        }

        mv.visitLabel(report)
        val failed = Label()
        mv.visitVarInsn(ALOAD, 4)
        mv.visitJumpInsn(IFNONNULL, failed)
        emitTimed(mv, TestProtocol.PASSED, c.fqn, nameLocal = 3, startLocal = 6, throwableLocal = null)
        mv.visitInsn(ICONST_0)
        mv.visitInsn(IRETURN)
        mv.visitLabel(failed)
        emitTimed(mv, TestProtocol.FAILED, c.fqn, nameLocal = 3, startLocal = 6, throwableLocal = 4)
        mv.visitInsn(ICONST_1)
        mv.visitInsn(IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** No throw → fail with "expected X"; a throw that is an X → pass; any other throw stays the failure. */
    private fun checkExpected(mv: MethodVisitor, expected: String) {
        val threw = Label(); val done = Label()
        mv.visitVarInsn(ALOAD, 4)
        mv.visitJumpInsn(IFNONNULL, threw)
        mv.visitTypeInsn(NEW, "java/lang/AssertionError")
        mv.visitInsn(DUP)
        mv.visitLdcInsn("Expected exception: " + expected.replace('/', '.'))
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false)
        mv.visitVarInsn(ASTORE, 4)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(threw)
        mv.visitVarInsn(ALOAD, 4)
        mv.visitTypeInsn(INSTANCEOF, expected)
        mv.visitJumpInsn(IFEQ, done)
        mv.visitInsn(ACONST_NULL)
        mv.visitVarInsn(ASTORE, 4)
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

    /** A static hook is called on its declaring class; an instance hook on the [cls] instance in [receiverLocal]. */
    private fun callHook(mv: MethodVisitor, cls: String, h: TestHook, receiverLocal: Int) {
        if (h.isStatic) {
            mv.visitMethodInsn(INVOKESTATIC, h.owner, h.name, h.descriptor, false)
        } else {
            mv.visitVarInsn(ALOAD, receiverLocal)
            mv.visitTypeInsn(CHECKCAST, cls)
            mv.visitMethodInsn(INVOKEVIRTUAL, cls, h.name, h.descriptor, false)
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
        when (value) {
            in -1..5 -> mv.visitInsn(ICONST_0 + value)
            in -128..127 -> mv.visitIntInsn(BIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun newBuilder(mv: MethodVisitor, initial: String) {
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitLdcInsn(initial)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
    }

    // ---- run-time helpers ------------------------------------------------------------------------------

    /**
     * `static Iterator iter(Object source)`: what a parameter source returned, as an iterator, accepting what JUnit
     * accepts: a Stream (primitive ones included), an Iterable, an Iterator, an object array or a primitive array.
     */
    private fun writeIter(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "iter", "(Ljava/lang/Object;)Ljava/util/Iterator;", null, null)
        mv.visitCode()
        fun case(type: String, body: () -> Unit) {
            val no = Label()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitTypeInsn(INSTANCEOF, type)
            mv.visitJumpInsn(IFEQ, no)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitTypeInsn(CHECKCAST, type)
            body()
            mv.visitInsn(ARETURN)
            mv.visitLabel(no)
        }
        case("java/util/stream/BaseStream") {
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/BaseStream", "iterator", "()Ljava/util/Iterator;", true)
        }
        case("java/lang/Iterable") {
            mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true)
        }
        case("java/util/Iterator") {}
        case("[Ljava/lang/Object;") {
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false)
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true)
        }
        for ((arr, stream) in listOf("[I" to "java/util/stream/IntStream", "[J" to "java/util/stream/LongStream", "[D" to "java/util/stream/DoubleStream")) {
            case(arr) {
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "stream", "($arr)L$stream;", false)
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/BaseStream", "iterator", "()Ljava/util/Iterator;", true)
            }
        }
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException")
        mv.visitInsn(DUP)
        mv.visitLdcInsn("A parameter source must return a Stream, Iterable, Iterator or array")
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(ATHROW)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * `static Object[] args(Object item, int arity)`: one item of a source as a test's arguments. An array is the
     * arguments themselves, except for a test taking one parameter (arity 1), where it is that one argument;
     * arity -1 (JUnit 4's `Parameterized`) always unwraps it.
     */
    private fun writeArgs(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "args", "(Ljava/lang/Object;I)[Ljava/lang/Object;", null, null)
        mv.visitCode()
        val single = Label()
        mv.visitVarInsn(ILOAD, 1)
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(IF_ICMPEQ, single)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitTypeInsn(INSTANCEOF, "[Ljava/lang/Object;")
        mv.visitJumpInsn(IFEQ, single)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;")
        mv.visitInsn(ARETURN)
        mv.visitLabel(single)
        mv.visitInsn(ICONST_1)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        mv.visitInsn(DUP)
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitInsn(AASTORE)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** `static Object[] argsJ(Object item, int arity)`: [writeArgs], reading JUnit 5's `Arguments` first. Written only
     *  when a test has a `@MethodSource`, whose project then has junit-jupiter-params for `Arguments` to load from. */
    private fun writeJupiterArgs(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "argsJ", "(Ljava/lang/Object;I)[Ljava/lang/Object;", null, null)
        mv.visitCode()
        val plain = Label()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitTypeInsn(INSTANCEOF, JUPITER_ARGUMENTS)
        mv.visitJumpInsn(IFEQ, plain)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitTypeInsn(CHECKCAST, JUPITER_ARGUMENTS)
        mv.visitMethodInsn(INVOKEINTERFACE, JUPITER_ARGUMENTS, "get", "()[Ljava/lang/Object;", true)
        mv.visitInsn(ARETURN)
        mv.visitLabel(plain)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ILOAD, 1)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "args", "(Ljava/lang/Object;I)[Ljava/lang/Object;", false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** `static String join(Object[] args)`: the arguments as a run's name shows them, `1, abc, null`. */
    private fun writeJoin(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_STATIC, "join", "([Ljava/lang/Object;)Ljava/lang/String;", null, null)
        mv.visitCode()
        // locals: 0 args, 1 builder, 2 i
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        mv.visitVarInsn(ASTORE, 1)
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ISTORE, 2)
        val loop = Label(); val done = Label(); val noComma = Label()
        mv.visitLabel(loop)
        mv.visitVarInsn(ILOAD, 2)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitInsn(ARRAYLENGTH)
        mv.visitJumpInsn(IF_ICMPGE, done)
        mv.visitVarInsn(ILOAD, 2)
        mv.visitJumpInsn(IFEQ, noComma)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitLdcInsn(", "); append(mv, "Ljava/lang/String;")
        mv.visitInsn(POP)
        mv.visitLabel(noComma)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ILOAD, 2)
        mv.visitInsn(AALOAD)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false)
        append(mv, "Ljava/lang/String;")
        mv.visitInsn(POP)
        mv.visitIincInsn(2, 1)
        mv.visitJumpInsn(GOTO, loop)
        mv.visitLabel(done)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private const val JUPITER_ARGUMENTS = "org/junit/jupiter/params/provider/Arguments"

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

    /** `emit(event, cls, name, elapsedMillis, describe(t) or "")` for the run name in [nameLocal]. */
    private fun emitTimed(mv: MethodVisitor, event: String, cls: String, nameLocal: Int, startLocal: Int, throwableLocal: Int?) {
        mv.visitLdcInsn(event)
        mv.visitLdcInsn(cls)
        mv.visitVarInsn(ALOAD, nameLocal)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LLOAD, startLocal)
        mv.visitInsn(LSUB)
        mv.visitLdcInsn(1_000_000L)
        mv.visitInsn(LDIV)
        if (throwableLocal == null) mv.visitLdcInsn("") else concatDescribe(mv, "", throwableLocal)
        mv.visitMethodInsn(INVOKESTATIC, MAIN_CLASS, "emit", EMIT_DESC, false)
    }

    /** `emit(event, cls, name, 0L, message)` for the run name in [nameLocal]. */
    private fun emitNamed(mv: MethodVisitor, event: String, cls: String, nameLocal: Int, message: String = "") {
        mv.visitLdcInsn(event)
        mv.visitLdcInsn(cls)
        mv.visitVarInsn(ALOAD, nameLocal)
        mv.visitInsn(LCONST_0)
        mv.visitLdcInsn(message)
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
