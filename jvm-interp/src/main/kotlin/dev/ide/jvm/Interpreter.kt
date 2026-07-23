package dev.ide.jvm

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.util.concurrent.atomic.AtomicReference

private const val MODE_NEXT = 0
private const val MODE_GOTO = 1
private const val MODE_RET = 2

// Mask for the periodic cancellation check: `steps and INTERVAL == 0` fires once every 1024 instructions,
// cheap enough to leave in the hot loop yet frequent enough to unwind a busy loop within microseconds.
private const val CANCEL_CHECK_INTERVAL = 0x3FFL

// Stack-entry and local-slot kinds. A primitive lives unboxed in the frame's long array (floats and doubles
// as raw bits); a reference lives in the object array. Named K_* to avoid shadowing the Opcodes.T_* NEWARRAY
// operand constants brought in by the star import.
private const val K_REF: Byte = 0
private const val K_INT: Byte = 1
private const val K_LONG: Byte = 2
private const val K_FLOAT: Byte = 3
private const val K_DOUBLE: Byte = 4

/** Owner of Kotlin's reification marker intrinsic. */
private const val REIFIED_MARKER_OWNER = "kotlin/jvm/internal/Intrinsics"
/** `ReifiedTypeInliner.OperationKind` ids the interpreter can reproduce: NEW_ARRAY(0), AS(1), SAFE_AS(2),
 *  IS(3), JAVA_CLASS(4, `T::class.java`). ENUM(5, `enumValues`)/TYPE_OF(6, `typeOf`) are handled ahead of the
 *  VM (enum) or need kotlin-reflect (typeOf); a marker of those kinds throws so the caller falls back. */
private val REIFIED_KINDS_SUPPORTED = setOf(0, 1, 2, 3, 4)

/**
 * The execution engine. It runs one [MethodNode]'s bytecode over a typed frame (see [Frame]), dispatching
 * calls back through the [vm]: an interpreted target recurses into a fresh frame, and any other target goes
 * to the bridge. All values follow the conventions described in [Descriptors]; primitives stay unboxed on the
 * operand stack and in locals, and box only where a value crosses a boundary that stores objects (the bridge,
 * fields, arrays, lambda captures).
 *
 * Frames are pooled by call depth (calls are strictly nested on the host stack), so a warm call allocates
 * nothing. The pool and the reification scratch are per-thread ([Exec]), so multiple real threads run
 * interpreted code in parallel; the decoded-method and call-shape caches are shared and concurrent.
 */
internal class Interpreter(private val vm: Vm) {

    private companion object {
        /** Recipe tags in a `StringConcatFactory` recipe: a dynamic argument slot, and a constant slot. */
        const val TAG_ARG: Char = '\u0001'
        const val TAG_CONST: Char = '\u0002'
    }

    /** A method decoded once and reused across every invocation and recomposition: the instruction array, the
     *  label-to-index map, resolved jump targets, where each parameter binds in the locals array, and the
     *  resolved exception handlers. */
    private class MethodBlock(m: MethodNode) {
        val insns: Array<AbstractInsnNode> = m.instructions.toArray()
        val labelIndex: HashMap<LabelNode, Int> = HashMap<LabelNode, Int>().also { map ->
            insns.forEachIndexed { i, n -> if (n is LabelNode) map[n] = i }
        }

        /** The resolved instruction index of each [JumpInsnNode]'s label, -1 elsewhere (one map probe per
         *  branch otherwise, on the hottest paths). */
        val jumpTargets: IntArray = IntArray(insns.size) { i ->
            val n = insns[i]
            if (n is JumpInsnNode) labelIndex.getValue(n.label) else -1
        }

        val maxLocals: Int = m.maxLocals
        val maxStack: Int = m.maxStack
        val isStatic: Boolean = m.access and ACC_STATIC != 0
        val isSynchronized: Boolean = m.access and ACC_SYNCHRONIZED != 0
        val sig: String = m.name + m.desc

        val paramDescs: Array<String> = Descriptors.paramTypes(m.desc).toTypedArray()

        /** The locals index each parameter binds to (a category-2 parameter occupies two slots, and index 0 is
         *  the receiver for an instance method). */
        val paramSlots: IntArray = run {
            val out = IntArray(paramDescs.size)
            var slot = if (isStatic) 0 else 1
            for (i in paramDescs.indices) { out[i] = slot; slot += if (Descriptors.isCategory2(paramDescs[i])) 2 else 1 }
            out
        }

        class Handler(val start: Int, val end: Int, val handler: Int, val type: String?)

        val handlers: List<Handler> = m.tryCatchBlocks.mapNotNull { tc ->
            val start = labelIndex[tc.start] ?: return@mapNotNull null
            val end = labelIndex[tc.end] ?: return@mapNotNull null
            val handler = labelIndex[tc.handler] ?: return@mapNotNull null
            Handler(start, end, handler, tc.type)
        }
    }

    private val blocks = java.util.concurrent.ConcurrentHashMap<MethodNode, MethodBlock>()
    private fun blockFor(m: MethodNode): MethodBlock = blocks.getOrPut(m) { MethodBlock(m) }

    /** A method descriptor decoded once: the parameter descriptors and the return descriptor (parsing these
     *  per invocation allocated a list on every call). */
    private class CallShape(desc: String) {
        val params: Array<String> = Descriptors.paramTypes(desc).toTypedArray()
        val ret: String = Descriptors.returnType(desc)
    }

    private val shapes = java.util.concurrent.ConcurrentHashMap<String, CallShape>()
    private fun shape(desc: String): CallShape = shapes.getOrPut(desc) { CallShape(desc) }

    /**
     * Per-thread execution state. Each real thread that runs interpreted code (the program's main thread, and
     * every `Thread` it starts — which is a real host thread whose `run` re-enters here) gets its own frame
     * stack and reified/reification scratch, so threads execute genuinely in parallel with no shared mutable
     * frame state. Held in a [ThreadLocal]; fetched once per frame (never per instruction) and threaded down
     * the call so the hot loop touches only plain fields.
     */
    private class Exec {
        /** Frames pooled by call depth: interpreted calls nest strictly (they recurse on the host stack), so the
         *  frame at each depth is reused across every call that reaches it on this thread. */
        val pool = ArrayList<Frame>()
        var depth = 0

        /** Bytecode instructions this thread has executed; drives the cancel-check cadence and [steps]. */
        var localSteps = 0L

        /** Reified-type substitution for a running reified inline function on this thread: type-parameter name
         *  (`R`) → the JVM internal name of the concrete type argument. Set by [withReifiedTypes], empty
         *  otherwise. */
        var reifiedTypes: Map<String, String> = emptyMap()

        /** The concrete type a just-executed [reifiedOperationMarker] armed, consumed by the very next type op
         *  (`INSTANCEOF`/`CHECKCAST`/`ANEWARRAY`); the compiler always emits that op immediately after. */
        var pendingReified: String? = null
    }

    private val execLocal = ThreadLocal.withInitial { Exec() }

    /** Bytecode instructions executed by the CURRENT thread (diagnostics / throughput). Readers measure a
     *  before/after delta on the thread that runs the interpretation, so a per-thread count is exact for them. */
    val steps: Long get() = execLocal.get().localSteps

    /** Set (from any thread) to cancel a running program. Every thread's instruction loop checks it once every
     *  [CANCEL_CHECK_INTERVAL] instructions, so even a tight compute loop with no bridge calls unwinds promptly. */
    @Volatile @JvmField var cancelRequested: Boolean = false

    /** Run [body] applying reified [types] to this thread's reified operations; see [Vm.withReifiedTypes]. */
    internal fun <T> withReifiedTypes(types: Map<String, String>, body: () -> T): T {
        val exec = execLocal.get()
        val prev = exec.reifiedTypes
        exec.reifiedTypes = types
        return try { body() } finally { exec.reifiedTypes = prev }
    }

    /**
     * One invocation's operand stack and locals, typed: primitives live unboxed in [prim] (floats and doubles
     * as raw bits) with their [kind] recorded per entry, references in [ref]. Locals mirror the layout without
     * kinds (verified bytecode reads a slot with the type it stored). The control-flow outcome of the current
     * instruction lives here too, so branches and returns allocate nothing.
     */
    private class Frame {
        @JvmField var ref: Array<Any?> = arrayOfNulls(16)
        @JvmField var prim: LongArray = LongArray(16)
        @JvmField var kind: ByteArray = ByteArray(16)
        @JvmField var sp: Int = 0
        @JvmField var lRef: Array<Any?> = arrayOfNulls(8)
        @JvmField var lPrim: LongArray = LongArray(8)
        @JvmField var mode: Int = MODE_NEXT
        @JvmField var target: Int = 0
        @JvmField var retBoxed: Any? = null
        @JvmField var sig: String = "" // the method this frame runs, for diagnostics

        /** Size the arrays for a method and reset. Reference cells are cleared so a pooled frame does not keep
         *  the previous invocation's objects reachable. */
        fun prepare(maxStack: Int, maxLocals: Int) {
            val stackSize = maxStack + 2
            if (ref.size < stackSize) {
                ref = arrayOfNulls(stackSize); prim = LongArray(stackSize); kind = ByteArray(stackSize)
            } else {
                java.util.Arrays.fill(ref, null)
            }
            if (lRef.size < maxLocals) {
                lRef = arrayOfNulls(maxLocals); lPrim = LongArray(maxLocals)
            } else {
                java.util.Arrays.fill(lRef, null)
            }
            sp = 0
            retBoxed = null
        }

        fun pushI(v: Int) { prim[sp] = v.toLong(); kind[sp] = K_INT; ref[sp] = null; sp++ }
        fun pushJ(v: Long) { prim[sp] = v; kind[sp] = K_LONG; ref[sp] = null; sp++ }
        fun pushF(v: Float) { prim[sp] = v.toRawBits().toLong(); kind[sp] = K_FLOAT; ref[sp] = null; sp++ }
        fun pushD(v: Double) { prim[sp] = v.toRawBits(); kind[sp] = K_DOUBLE; ref[sp] = null; sp++ }
        fun pushRef(v: Any?) { ref[sp] = v; kind[sp] = K_REF; sp++ }

        fun popI(): Int = prim[--sp].toInt()
        fun popJ(): Long = prim[--sp]
        fun popF(): Float = Float.fromBits(prim[--sp].toInt())
        fun popD(): Double = Double.fromBits(prim[--sp])
        fun popRef(): Any? { val v = ref[--sp]; ref[sp] = null; return v }

        fun peekRef(): Any? = ref[sp - 1]

        /** The reference [depth] entries below the top (0 is the top entry). */
        fun refAt(depth: Int): Any? = ref[sp - 1 - depth]

        /** Push [v] as the kind [desc] names, unboxing a primitive (null defaults a primitive to zero). */
        fun pushByDesc(desc: String, v: Any?) {
            when (desc[0]) {
                'I', 'Z', 'B', 'C', 'S' -> pushI(v as? Int ?: 0)
                'J' -> pushJ(v as? Long ?: 0L)
                'F' -> pushF(v as? Float ?: 0f)
                'D' -> pushD(v as? Double ?: 0.0)
                else -> pushRef(v)
            }
        }

        /** Pop a value of the kind [desc] names, boxing a primitive. */
        fun popByDesc(desc: String): Any? = when (desc[0]) {
            'I', 'Z', 'B', 'C', 'S' -> popI()
            'J' -> popJ()
            'F' -> popF()
            'D' -> popD()
            else -> popRef()
        }

        fun popAny() { sp--; ref[sp] = null }
        fun clearStack() { java.util.Arrays.fill(ref, 0, sp, null); sp = 0 }

        private fun isCat2(i: Int) = kind[i] == K_LONG || kind[i] == K_DOUBLE
        private fun copyEntry(from: Int, to: Int) { ref[to] = ref[from]; prim[to] = prim[from]; kind[to] = kind[from] }

        fun dup() { copyEntry(sp - 1, sp); sp++ }
        fun dupX1() { copyEntry(sp - 1, sp); copyEntry(sp - 2, sp - 1); copyEntry(sp, sp - 2); sp++ }
        fun dupX2() {
            if (isCat2(sp - 2)) dupX1()
            else { copyEntry(sp - 1, sp); copyEntry(sp - 2, sp - 1); copyEntry(sp - 3, sp - 2); copyEntry(sp, sp - 3); sp++ }
        }
        fun dup2() {
            if (isCat2(sp - 1)) dup()
            else { copyEntry(sp - 2, sp); copyEntry(sp - 1, sp + 1); sp += 2 }
        }
        fun dup2X1() {
            if (isCat2(sp - 1)) dupX1()
            else {
                copyEntry(sp - 2, sp); copyEntry(sp - 1, sp + 1)
                copyEntry(sp - 3, sp - 1)
                copyEntry(sp, sp - 3); copyEntry(sp + 1, sp - 2)
                sp += 2
            }
        }
        fun dup2X2() {
            if (isCat2(sp - 1)) {
                if (isCat2(sp - 2)) dupX1()
                else { copyEntry(sp - 1, sp); copyEntry(sp - 2, sp - 1); copyEntry(sp - 3, sp - 2); copyEntry(sp, sp - 3); sp++ }
            } else if (isCat2(sp - 3)) {
                copyEntry(sp - 2, sp); copyEntry(sp - 1, sp + 1)
                copyEntry(sp - 3, sp - 1)
                copyEntry(sp, sp - 3); copyEntry(sp + 1, sp - 2)
                sp += 2
            } else {
                copyEntry(sp - 2, sp); copyEntry(sp - 1, sp + 1)
                copyEntry(sp - 4, sp - 2); copyEntry(sp - 3, sp - 1)
                copyEntry(sp, sp - 4); copyEntry(sp + 1, sp - 3)
                sp += 2
            }
        }
        fun swap() { copyEntry(sp - 1, sp); copyEntry(sp - 2, sp - 1); copyEntry(sp, sp - 2) }
        fun pop2() { if (isCat2(sp - 1)) popAny() else { popAny(); popAny() } }
    }

    private fun acquire(exec: Exec, block: MethodBlock): Frame {
        if (exec.depth == exec.pool.size) exec.pool.add(Frame())
        val f = exec.pool[exec.depth]
        exec.depth++
        f.prepare(block.maxStack, block.maxLocals)
        f.sig = block.sig
        return f
    }

    private fun release(exec: Exec) { exec.depth-- }

    /**
     * Execute [m], declared in [owner], with [receiver] (null for a static method) and boxed [args]. Returns
     * the method's (boxed) result, or null for a void method. This is the boxed entry point used at the VM
     * boundary; interpreted-to-interpreted calls take the typed fast path in [callInterpreted] instead.
     */
    fun execute(owner: VmClass, m: MethodNode, receiver: Any?, args: List<Any?>): Any? {
        // Each thread owns its frame stack ([Exec]), so threads run interpreted code concurrently. This is the
        // boxed entry point (the VM boundary and re-entry from a peer callback / bridge); interpreted-to-
        // interpreted calls take the typed fast path in [callInterpreted]. Fetch the thread's Exec once here.
        val exec = execLocal.get()
        val block = blockFor(m)
        val frame = acquire(exec, block)
        try {
            if (!block.isStatic) frame.lRef[0] = receiver
            val slots = block.paramSlots
            val descs = block.paramDescs
            for (i in slots.indices) seedLocal(frame, slots[i], descs[i], args[i])
            return runFrame(block, frame, caller = null, exec = exec, declaring = owner)
        } finally {
            release(exec)
        }
    }

    /** Store a boxed argument into a typed local slot, by the parameter's declared kind (the runtime type of a
     *  boxed Integer passed for an Object parameter must not decide the slot kind). */
    private fun seedLocal(frame: Frame, slot: Int, desc: String, v: Any?) {
        when (desc[0]) {
            'I', 'Z', 'B', 'C', 'S' -> frame.lPrim[slot] = (v as Int).toLong()
            'J' -> frame.lPrim[slot] = v as Long
            'F' -> frame.lPrim[slot] = (v as Float).toRawBits().toLong()
            'D' -> frame.lPrim[slot] = (v as Double).toRawBits()
            else -> frame.lRef[slot] = v
        }
    }

    /** Call an interpreted method with its arguments taken directly from [caller]'s typed stack (no boxing):
     *  each argument entry is copied into the callee's local slot, and the callee's return is pushed back onto
     *  [caller] typed. */
    private fun callInterpreted(declaring: VmClass, m: MethodNode, hasReceiver: Boolean, caller: Frame, exec: Exec) {
        val block = blockFor(m)
        val callee = acquire(exec, block)
        try {
            val slots = block.paramSlots
            // Verified bytecode never underflows, so a short caller stack means the interpreter mis-tracked it
            // upstream. Fail with the method name instead of an opaque ArrayIndexOutOfBoundsException, and keep
            // it catchable (a preview degrades rather than crashing). The finally still releases the frame.
            if (caller.sp < slots.size + (if (hasReceiver) 1 else 0))
                throw VmUnsupportedException("operand stack underflow calling ${declaring.name}.${m.name}${m.desc} from ${caller.sig}")
            for (i in slots.indices.reversed()) {
                caller.sp--
                val sp = caller.sp
                val slot = slots[i]
                if (caller.kind[sp] == K_REF) { callee.lRef[slot] = deref(caller.ref[sp]); caller.ref[sp] = null }
                else callee.lPrim[slot] = caller.prim[sp]
            }
            if (hasReceiver) callee.lRef[0] = deref(caller.popRef())
            runFrame(block, callee, caller, exec, declaring)
        } finally {
            release(exec)
        }
    }

    /** The instruction loop. With a [caller], a return value is pushed onto the caller's stack typed and null
     *  is returned; at the VM boundary (null caller) the return value is boxed and returned. A `synchronized`
     *  method (`ACC_SYNCHRONIZED`) locks the receiver (instance) or the class (static) around the whole run,
     *  released on every exit — normal return, a thrown/propagating exception, or cancellation. */
    private fun runFrame(block: MethodBlock, frame: Frame, caller: Frame?, exec: Exec, declaring: VmClass): Any? {
        val monitor = if (block.isSynchronized) enterSyncMethod(block, frame, declaring) else null
        try {
            return runLoop(block, frame, caller, exec)
        } finally {
            monitor?.exit()
        }
    }

    /** The monitor a `synchronized` method locks: the declaring class's monitor for a static method, else the
     *  receiver's (`synchronized void m()` locks `this`; `static synchronized void m()` locks the class). */
    private fun enterSyncMethod(block: MethodBlock, frame: Frame, declaring: VmClass): VmMonitor {
        val monitor = if (block.isStatic) vm.monitorFor(declaring) else vm.monitorFor(deref(frame.lRef[0]))
        monitor.enter()
        return monitor
    }

    private fun runLoop(block: MethodBlock, frame: Frame, caller: Frame?, exec: Exec): Any? {
        val insns = block.insns
        var pc = 0
        while (true) {
            val insn = insns[pc]
            val op = insn.opcode
            if (op < 0) { pc++; continue } // LabelNode, FrameNode, or LineNumberNode
            exec.localSteps++
            if (exec.localSteps and CANCEL_CHECK_INTERVAL == 0L && cancelRequested) throw VmInterruptedException()
            frame.mode = MODE_NEXT
            try {
                step(insn, op, pc, block, frame, caller, exec)
            } catch (ve: VmException) {
                val handler = findHandler(block, pc, ve.value)
                if (handler >= 0) { frame.clearStack(); frame.pushRef(ve.value); pc = handler; continue }
                throw ve
            }
            when (frame.mode) {
                MODE_GOTO -> pc = frame.target
                MODE_RET -> return frame.retBoxed
                else -> pc++
            }
        }
    }

    /** The instruction index of a handler covering [pc] and matching [thrown], or -1 if none applies. */
    private fun findHandler(block: MethodBlock, pc: Int, thrown: Any?): Int {
        for (h in block.handlers) {
            if (pc < h.start || pc >= h.end) continue
            if (h.type == null || matchesCatch(thrown, h.type)) return h.handler
        }
        return -1
    }

    private fun matchesCatch(thrown: Any?, catchType: String): Boolean = when (thrown) {
        is VmObject -> vm.isSubtype(thrown.vmClass, catchType)
        is Throwable -> runCatching { Class.forName(catchType.replace('/', '.'), false, javaClass.classLoader).isInstance(thrown) }.getOrDefault(false)
        else -> false
    }

    private fun step(insn: AbstractInsnNode, op: Int, pc: Int, block: MethodBlock, frame: Frame, caller: Frame?, exec: Exec) {
        when (op) {
            NOP -> {}
            ACONST_NULL -> frame.pushRef(null)
            in ICONST_M1..ICONST_5 -> frame.pushI(op - ICONST_0)
            LCONST_0 -> frame.pushJ(0L); LCONST_1 -> frame.pushJ(1L)
            FCONST_0 -> frame.pushF(0f); FCONST_1 -> frame.pushF(1f); FCONST_2 -> frame.pushF(2f)
            DCONST_0 -> frame.pushD(0.0); DCONST_1 -> frame.pushD(1.0)
            BIPUSH, SIPUSH -> frame.pushI((insn as IntInsnNode).operand)
            LDC -> ldc((insn as LdcInsnNode).cst, frame, exec)

            ILOAD -> frame.pushI(frame.lPrim[(insn as VarInsnNode).`var`].toInt())
            LLOAD -> frame.pushJ(frame.lPrim[(insn as VarInsnNode).`var`])
            FLOAD -> frame.pushF(Float.fromBits(frame.lPrim[(insn as VarInsnNode).`var`].toInt()))
            DLOAD -> frame.pushD(Double.fromBits(frame.lPrim[(insn as VarInsnNode).`var`]))
            ALOAD -> frame.pushRef(frame.lRef[(insn as VarInsnNode).`var`])
            ISTORE -> frame.lPrim[(insn as VarInsnNode).`var`] = frame.popI().toLong()
            LSTORE -> frame.lPrim[(insn as VarInsnNode).`var`] = frame.popJ()
            FSTORE -> frame.lPrim[(insn as VarInsnNode).`var`] = frame.popF().toRawBits().toLong()
            DSTORE -> frame.lPrim[(insn as VarInsnNode).`var`] = frame.popD().toRawBits()
            ASTORE -> frame.lRef[(insn as VarInsnNode).`var`] = frame.popRef()
            IINC -> { val n = insn as IincInsnNode; frame.lPrim[n.`var`] = (frame.lPrim[n.`var`].toInt() + n.incr).toLong() }

            POP -> frame.popAny(); POP2 -> frame.pop2()
            DUP -> frame.dup(); DUP_X1 -> frame.dupX1(); DUP_X2 -> frame.dupX2()
            DUP2 -> frame.dup2(); DUP2_X1 -> frame.dup2X1(); DUP2_X2 -> frame.dup2X2()
            SWAP -> frame.swap()

            // integer arithmetic
            IADD -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a + b) }
            ISUB -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a - b) }
            IMUL -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a * b) }
            IDIV -> { val b = frame.popI(); val a = frame.popI(); if (b == 0) throwReal(ArithmeticException("/ by zero")); frame.pushI(a / b) }
            IREM -> { val b = frame.popI(); val a = frame.popI(); if (b == 0) throwReal(ArithmeticException("/ by zero")); frame.pushI(a % b) }
            INEG -> frame.pushI(-frame.popI())
            ISHL -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a shl (b and 0x1f)) }
            ISHR -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a shr (b and 0x1f)) }
            IUSHR -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a ushr (b and 0x1f)) }
            IAND -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a and b) }
            IOR -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a or b) }
            IXOR -> { val b = frame.popI(); val a = frame.popI(); frame.pushI(a xor b) }

            // long arithmetic
            LADD -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a + b) }
            LSUB -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a - b) }
            LMUL -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a * b) }
            LDIV -> { val b = frame.popJ(); val a = frame.popJ(); if (b == 0L) throwReal(ArithmeticException("/ by zero")); frame.pushJ(a / b) }
            LREM -> { val b = frame.popJ(); val a = frame.popJ(); if (b == 0L) throwReal(ArithmeticException("/ by zero")); frame.pushJ(a % b) }
            LNEG -> frame.pushJ(-frame.popJ())
            LSHL -> { val b = frame.popI(); val a = frame.popJ(); frame.pushJ(a shl (b and 0x3f)) }
            LSHR -> { val b = frame.popI(); val a = frame.popJ(); frame.pushJ(a shr (b and 0x3f)) }
            LUSHR -> { val b = frame.popI(); val a = frame.popJ(); frame.pushJ(a ushr (b and 0x3f)) }
            LAND -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a and b) }
            LOR -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a or b) }
            LXOR -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushJ(a xor b) }

            // float and double arithmetic
            FADD -> { val b = frame.popF(); val a = frame.popF(); frame.pushF(a + b) }
            FSUB -> { val b = frame.popF(); val a = frame.popF(); frame.pushF(a - b) }
            FMUL -> { val b = frame.popF(); val a = frame.popF(); frame.pushF(a * b) }
            FDIV -> { val b = frame.popF(); val a = frame.popF(); frame.pushF(a / b) }
            FREM -> { val b = frame.popF(); val a = frame.popF(); frame.pushF(a % b) }
            FNEG -> frame.pushF(-frame.popF())
            DADD -> { val b = frame.popD(); val a = frame.popD(); frame.pushD(a + b) }
            DSUB -> { val b = frame.popD(); val a = frame.popD(); frame.pushD(a - b) }
            DMUL -> { val b = frame.popD(); val a = frame.popD(); frame.pushD(a * b) }
            DDIV -> { val b = frame.popD(); val a = frame.popD(); frame.pushD(a / b) }
            DREM -> { val b = frame.popD(); val a = frame.popD(); frame.pushD(a % b) }
            DNEG -> frame.pushD(-frame.popD())

            // conversions
            I2L -> frame.pushJ(frame.popI().toLong()); I2F -> frame.pushF(frame.popI().toFloat()); I2D -> frame.pushD(frame.popI().toDouble())
            L2I -> frame.pushI(frame.popJ().toInt()); L2F -> frame.pushF(frame.popJ().toFloat()); L2D -> frame.pushD(frame.popJ().toDouble())
            F2I -> frame.pushI(frame.popF().toInt()); F2L -> frame.pushJ(frame.popF().toLong()); F2D -> frame.pushD(frame.popF().toDouble())
            D2I -> frame.pushI(frame.popD().toInt()); D2L -> frame.pushJ(frame.popD().toLong()); D2F -> frame.pushF(frame.popD().toFloat())
            I2B -> frame.pushI(frame.popI().toByte().toInt()); I2C -> frame.pushI(frame.popI().toChar().code); I2S -> frame.pushI(frame.popI().toShort().toInt())

            // comparisons producing an int
            LCMP -> { val b = frame.popJ(); val a = frame.popJ(); frame.pushI(a.compareTo(b)) }
            FCMPL -> { val b = frame.popF(); val a = frame.popF(); frame.pushI(fcmp(a, b, -1)) }
            FCMPG -> { val b = frame.popF(); val a = frame.popF(); frame.pushI(fcmp(a, b, 1)) }
            DCMPL -> { val b = frame.popD(); val a = frame.popD(); frame.pushI(dcmp(a, b, -1)) }
            DCMPG -> { val b = frame.popD(); val a = frame.popD(); frame.pushI(dcmp(a, b, 1)) }

            // branches
            IFEQ -> if (frame.popI() == 0) goTo(pc, block, frame)
            IFNE -> if (frame.popI() != 0) goTo(pc, block, frame)
            IFLT -> if (frame.popI() < 0) goTo(pc, block, frame)
            IFGE -> if (frame.popI() >= 0) goTo(pc, block, frame)
            IFGT -> if (frame.popI() > 0) goTo(pc, block, frame)
            IFLE -> if (frame.popI() <= 0) goTo(pc, block, frame)
            IF_ICMPEQ -> { val b = frame.popI(); val a = frame.popI(); if (a == b) goTo(pc, block, frame) }
            IF_ICMPNE -> { val b = frame.popI(); val a = frame.popI(); if (a != b) goTo(pc, block, frame) }
            IF_ICMPLT -> { val b = frame.popI(); val a = frame.popI(); if (a < b) goTo(pc, block, frame) }
            IF_ICMPGE -> { val b = frame.popI(); val a = frame.popI(); if (a >= b) goTo(pc, block, frame) }
            IF_ICMPGT -> { val b = frame.popI(); val a = frame.popI(); if (a > b) goTo(pc, block, frame) }
            IF_ICMPLE -> { val b = frame.popI(); val a = frame.popI(); if (a <= b) goTo(pc, block, frame) }
            IF_ACMPEQ -> { val b = deref(frame.popRef()); val a = deref(frame.popRef()); if (a === b) goTo(pc, block, frame) }
            IF_ACMPNE -> { val b = deref(frame.popRef()); val a = deref(frame.popRef()); if (a !== b) goTo(pc, block, frame) }
            IFNULL -> if (deref(frame.popRef()) == null) goTo(pc, block, frame)
            IFNONNULL -> if (deref(frame.popRef()) != null) goTo(pc, block, frame)
            GOTO -> goTo(pc, block, frame)

            TABLESWITCH -> {
                val n = insn as TableSwitchInsnNode
                val key = frame.popI()
                val target = if (key < n.min || key > n.max) n.dflt else n.labels[key - n.min]
                frame.mode = MODE_GOTO; frame.target = block.labelIndex.getValue(target)
            }
            LOOKUPSWITCH -> {
                val n = insn as LookupSwitchInsnNode
                val key = frame.popI()
                val i = n.keys.indexOf(key)
                frame.mode = MODE_GOTO; frame.target = block.labelIndex.getValue(if (i >= 0) n.labels[i] else n.dflt)
            }

            // returns: push onto the caller typed, or box at the VM boundary
            IRETURN -> { val v = frame.popI(); if (caller != null) caller.pushI(v) else frame.retBoxed = v; frame.mode = MODE_RET }
            LRETURN -> { val v = frame.popJ(); if (caller != null) caller.pushJ(v) else frame.retBoxed = v; frame.mode = MODE_RET }
            FRETURN -> { val v = frame.popF(); if (caller != null) caller.pushF(v) else frame.retBoxed = v; frame.mode = MODE_RET }
            DRETURN -> { val v = frame.popD(); if (caller != null) caller.pushD(v) else frame.retBoxed = v; frame.mode = MODE_RET }
            ARETURN -> { val v = deref(frame.popRef()); if (caller != null) caller.pushRef(v) else frame.retBoxed = v; frame.mode = MODE_RET }
            RETURN -> { frame.retBoxed = null; frame.mode = MODE_RET }

            // fields
            GETSTATIC -> { val f = insn as FieldInsnNode; frame.pushByDesc(f.desc, staticGet(f.owner, f.name, f.desc)) }
            PUTSTATIC -> { val f = insn as FieldInsnNode; staticPut(f.owner, f.name, f.desc, deref(frame.popByDesc(f.desc))) }
            GETFIELD -> { val f = insn as FieldInsnNode; frame.pushByDesc(f.desc, fieldGet(deref(frame.popRef()), f.name, f.desc)) }
            PUTFIELD -> { val f = insn as FieldInsnNode; val v = deref(frame.popByDesc(f.desc)); fieldPut(deref(frame.popRef()), f.name, f.desc, v) }

            // method invocation
            INVOKESTATIC -> invokeStatic(insn as MethodInsnNode, frame, exec)
            INVOKESPECIAL -> invokeSpecial(insn as MethodInsnNode, frame, exec)
            INVOKEVIRTUAL, INVOKEINTERFACE -> invokeVirtual(insn as MethodInsnNode, frame, exec)
            INVOKEDYNAMIC -> invokeDynamic(insn as InvokeDynamicInsnNode, frame)

            // object and array creation
            NEW -> frame.pushRef(instantiate((insn as TypeInsnNode).desc))
            NEWARRAY -> frame.pushRef(VmArray.of(primitiveArrayDesc((insn as IntInsnNode).operand), frame.popI()))
            ANEWARRAY -> { val t = insn as TypeInsnNode; val et = takePendingReified(exec) ?: t.desc; frame.pushRef(VmArray.of(elementDescOf(et), frame.popI())) }
            MULTIANEWARRAY -> frame.pushRef(multiArray(insn as MultiANewArrayInsnNode, frame))
            ARRAYLENGTH -> frame.pushI(arrayLength(deref(frame.popRef())))

            IALOAD, BALOAD, CALOAD, SALOAD -> { val i = frame.popI(); frame.pushI(arrayGet(deref(frame.popRef()), i) as? Int ?: 0) }
            LALOAD -> { val i = frame.popI(); frame.pushJ(arrayGet(deref(frame.popRef()), i) as? Long ?: 0L) }
            FALOAD -> { val i = frame.popI(); frame.pushF(arrayGet(deref(frame.popRef()), i) as? Float ?: 0f) }
            DALOAD -> { val i = frame.popI(); frame.pushD(arrayGet(deref(frame.popRef()), i) as? Double ?: 0.0) }
            AALOAD -> { val i = frame.popI(); frame.pushRef(arrayGet(deref(frame.popRef()), i)) }
            IASTORE, BASTORE, CASTORE, SASTORE -> { val v = frame.popI(); val i = frame.popI(); arraySet(deref(frame.popRef()), i, v) }
            LASTORE -> { val v = frame.popJ(); val i = frame.popI(); arraySet(deref(frame.popRef()), i, v) }
            FASTORE -> { val v = frame.popF(); val i = frame.popI(); arraySet(deref(frame.popRef()), i, v) }
            DASTORE -> { val v = frame.popD(); val i = frame.popI(); arraySet(deref(frame.popRef()), i, v) }
            AASTORE -> { val v = deref(frame.popRef()); val i = frame.popI(); arraySet(deref(frame.popRef()), i, v) }

            CHECKCAST -> { val t = takePendingReified(exec) ?: (insn as TypeInsnNode).desc; if (!castOk(deref(frame.peekRef()), t)) throwReal(ClassCastException("cannot cast to $t")) }
            INSTANCEOF -> { val t = takePendingReified(exec) ?: (insn as TypeInsnNode).desc; frame.pushI(if (instanceOf(deref(frame.popRef()), t)) 1 else 0) }

            ATHROW -> { val t = deref(frame.popRef()); if (t == null) throwReal(NullPointerException()); throw VmException(t) }
            MONITORENTER -> vm.monitorFor(deref(frame.popRef())).enter()
            MONITOREXIT -> vm.monitorFor(deref(frame.popRef())).exit()

            else -> throw VmUnsupportedException("opcode $op is not supported")
        }
    }

    private fun goTo(pc: Int, block: MethodBlock, frame: Frame) {
        frame.mode = MODE_GOTO
        frame.target = block.jumpTargets[pc]
    }

    private fun fcmp(a: Float, b: Float, nan: Int): Int = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b)
    private fun dcmp(a: Double, b: Double, nan: Int): Int = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b)

    private fun throwReal(t: Throwable): Nothing = throw VmException(t)

    private fun ldc(cst: Any, frame: Frame, exec: Exec) {
        when (cst) {
            is Int -> frame.pushI(cst)
            is Long -> frame.pushJ(cst)
            is Float -> frame.pushF(cst)
            is Double -> frame.pushD(cst)
            // A class literal (`Foo.class`, `int[].class`). Under a pending reified marker (`T::class.java`,
            // OperationKind JAVA_CLASS) the erased placeholder is replaced by the concrete type's class literal.
            is Type -> takePendingReified(exec).let { t ->
                frame.pushRef(vm.classLiteral(if (t != null) Type.getObjectType(t) else cst))
            }
            else -> frame.pushRef(cst) // String (or a constant the bridge understands as a reference)
        }
    }

    // ---- fields -----------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun staticGet(owner: String, name: String, desc: String): Any? {
        val cls = vm.resolve(owner) ?: return vm.bridge.getStatic(owner, name, desc)
        vm.ensureInitialized(cls)
        // A static field the interpreted class inherits from a REAL superclass (e.g. `View.EMPTY_STATE_SET`
        // read through an interpreted View subclass): the interpreted chain doesn't declare it, so read it from
        // the nearest real superclass, whose name is loadable. Mirrors invokeStatic's inherited-method bridge.
        val holder = ownerHolding(cls, name)
            ?: return vm.bridge.getStatic(vm.realSuperName(cls), name, desc)
        val slot = holder.statics[name]
        return if (name in holder.volatileStaticFields) (slot as AtomicReference<Any?>).get() else slot
    }

    @Suppress("UNCHECKED_CAST")
    private fun staticPut(owner: String, name: String, desc: String, value: Any?) {
        val cls = vm.resolve(owner) ?: return vm.bridge.putStatic(owner, name, desc, vm.toReal(value))
        vm.ensureInitialized(cls)
        val holder = ownerHolding(cls, name)
            ?: return vm.bridge.putStatic(vm.realSuperName(cls), name, desc, vm.toReal(value))
        if (name in holder.volatileStaticFields) (holder.statics[name] as AtomicReference<Any?>).set(value)
        else holder.statics[name] = value
    }

    /** The interpreted class in [start]'s chain that declares static field [name], or null when no interpreted
     *  class declares it — meaning it is inherited from a real supertype and must be read/written through the
     *  bridge (a static is stored on its declaring class). */
    private fun ownerHolding(start: VmClass, name: String): VmClass? {
        var c: VmClass? = start
        while (c != null) { if (name in c.staticFieldDescs) return c; c = c.superName?.let { vm.resolve(it) } }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldGet(receiver: Any?, name: String, desc: String): Any? {
        if (receiver == null) throwReal(NullPointerException("getfield $name on null"))
        if (receiver !is VmObject) return vm.bridge.getField(receiver, name, desc)
        // A field an interpreted class declares lives on the VmObject; a field inherited from a real supertype
        // lives on the peer (regardless of the field-reference owner the compiler emitted). A volatile field's
        // value lives inside an AtomicReference holder for real volatile read semantics.
        if (!vm.vmDeclaresField(receiver.vmClass, name)) return vm.bridge.getField(vm.peerOf(receiver), name, desc)
        val slot = receiver.fields[name]
        return if (vm.isVolatileInstanceField(receiver.vmClass, name)) (slot as AtomicReference<Any?>).get() else slot
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldPut(receiver: Any?, name: String, desc: String, value: Any?) {
        if (receiver == null) throwReal(NullPointerException("putfield $name on null"))
        if (receiver !is VmObject) { vm.bridge.putField(receiver, name, desc, vm.toReal(value)); return }
        if (!vm.vmDeclaresField(receiver.vmClass, name)) { vm.bridge.putField(vm.peerOf(receiver), name, desc, vm.toReal(value)); return }
        if (vm.isVolatileInstanceField(receiver.vmClass, name)) (receiver.fields[name] as AtomicReference<Any?>).set(value)
        else receiver.fields[name] = value
    }

    // ---- invocation -------------------------------------------------------------------------------

    /** Pop [sh]'s arguments boxed (for a bridge or lambda target), restoring left-to-right order. */
    private fun popBoxedArgs(sh: CallShape, frame: Frame): List<Any?> {
        val n = sh.params.size
        if (n == 0) return emptyList()
        val args = arrayOfNulls<Any?>(n)
        for (i in n - 1 downTo 0) args[i] = deref(frame.popByDesc(sh.params[i]))
        return args.asList()
    }

    private fun pushResult(sh: CallShape, result: Any?, frame: Frame) {
        if (sh.ret != "V") frame.pushByDesc(sh.ret, result)
    }

    /** The concrete type a preceding [reifiedOperationMarker] armed, consumed once (cleared). Null when no
     *  marker is pending — the type op then uses its own (erased placeholder) operand. */
    private fun takePendingReified(exec: Exec): String? {
        val p = exec.pendingReified
        exec.pendingReified = null
        return p
    }

    private fun invokeStatic(insn: MethodInsnNode, frame: Frame, exec: Exec) {
        // Kotlin's reification marker: `reifiedOperationMarker(int operationKind, String typeParameterName)`.
        // Calling it directly throws (it exists only for the compiler's inliner to strip); here we reproduce the
        // inliner's transform live — consume the operands and ARM the concrete type for the following type op,
        // so `INSTANCEOF`/`CHECKCAST`/`ANEWARRAY` run against the real type argument. Kinds: 0=NEW_ARRAY, 1=AS,
        // 2=SAFE_AS, 3=IS. Kinds we don't model (4=JAVA_CLASS, 5=ENUM, 6=TYPE_OF) fail cleanly so the caller can
        // fall back rather than silently mis-run.
        if (insn.owner == REIFIED_MARKER_OWNER && insn.name == "reifiedOperationMarker") {
            val typeName = deref(frame.popRef()) as? String
            val kind = frame.popI()
            if (kind !in REIFIED_KINDS_SUPPORTED) throw VmUnsupportedException("reified operation kind $kind is not supported")
            exec.pendingReified = typeName?.let { exec.reifiedTypes[it] }
                ?: throw VmUnsupportedException("no reified type bound for `$typeName`")
            return
        }
        val cls = vm.resolve(insn.owner)
        if (cls != null) {
            vm.ensureInitialized(cls)
            val target = vm.findInHierarchy(cls, insn.name, insn.desc)
            if (target != null) {
                callInterpreted(target.first, target.second, hasReceiver = false, caller = frame, exec = exec)
                return
            }
            // A static method the interpreted class inherits from a REAL superclass (e.g.
            // `ViewGroup.getChildMeasureSpec` called via an interpreted `Toolbar`): the interpreted chain
            // doesn't declare it, so bridge the call to the nearest real superclass, whose name IS loadable.
            val sh = shape(insn.desc)
            val args = popBoxedArgs(sh, frame)
            pushResult(sh, vm.bridgeStatic(vm.realSuperName(cls), insn.name, insn.desc, args), frame)
            return
        }
        val sh = shape(insn.desc)
        val args = popBoxedArgs(sh, frame)
        pushResult(sh, vm.bridgeStatic(insn.owner, insn.name, insn.desc, args), frame)
    }

    private fun invokeSpecial(insn: MethodInsnNode, frame: Frame, exec: Exec) {
        val cls = vm.resolve(insn.owner)
        if (cls != null) {
            val method = cls.declaredMethod(insn.name, insn.desc)
                ?: vm.findInHierarchy(cls, insn.name, insn.desc)?.second
            if (method != null) {
                callInterpreted(cls, method, hasReceiver = true, caller = frame, exec = exec)
                return
            }
            // The owner is interpreted but declares this method nowhere in its interpreted chain: it is a
            // `super` call to a method a REAL supertype provides (e.g. `super.setImageDrawable` reaching
            // ImageView from an interpreted View subclass). Fall through to the boxed dispatch, which routes it
            // to the peer's real super method.
        }
        // Constructing a bridged object, an interpreted super(...) into a real supertype, or a super call into
        // a real supertype: the boxed dispatch handles all of them.
        val sh = shape(insn.desc)
        val args = popBoxedArgs(sh, frame)
        val receiver = frame.popRef()
        pushResult(sh, dispatchSpecial(insn.owner, insn.name, insn.desc, receiver, args), frame)
    }

    private fun invokeVirtual(insn: MethodInsnNode, frame: Frame, exec: Exec) {
        val sh = shape(insn.desc)
        val receiver = deref(frame.refAt(sh.params.size))
        if (receiver is VmObject) {
            val found = vm.findInHierarchy(receiver.vmClass, insn.name, insn.desc)
            if (found != null) { callInterpreted(found.first, found.second, hasReceiver = true, caller = frame, exec = exec); return }
        }
        val args = popBoxedArgs(sh, frame)
        frame.popAny() // the receiver entry (already read)
        pushResult(sh, dispatchVirtual(insn.name, insn.desc, receiver, args), frame)
    }

    /** Invoke a static method with boxed arguments (the method-handle and external entry path). */
    private fun dispatchStatic(owner: String, name: String, descriptor: String, rawArgs: List<Any?>): Any? {
        val args = rawArgs.map { deref(it) }
        val cls = vm.resolve(owner) ?: return vm.bridgeStatic(owner, name, descriptor, args)
        vm.ensureInitialized(cls)
        val (declaring, method) = vm.findInHierarchy(cls, name, descriptor)
            ?: throw VmUnsupportedException("no static method $owner.$name$descriptor")
        return execute(declaring, method, receiver = null, args = args)
    }

    /**
     * Invoke a constructor, private method, or super method with boxed arguments (the `invokespecial` targets).
     * Constructing a bridged object runs its real constructor and stores the result in the [Uninitialized]
     * placeholder that `new` pushed. A super constructor into a bridged supertype (typically `Object.<init>`)
     * on an interpreted object has no real superclass to initialize, so it is a no-op.
     */
    private fun dispatchSpecial(owner: String, name: String, descriptor: String, rawReceiver: Any?, rawArgs: List<Any?>): Any? {
        if (name == "<init>" && rawReceiver is Uninitialized) {
            rawReceiver.real = vm.bridgeConstruct(owner, descriptor, rawArgs.map { deref(it) })
            return null
        }
        val receiver = deref(rawReceiver)
        val args = rawArgs.map { deref(it) }
        val cls = vm.resolve(owner)
        return when {
            // The interpreted `super(...)` into a real supertype: create the peer, invoking that real
            // superclass constructor with these arguments, so its real state is initialized.
            cls == null && name == "<init>" && receiver is VmObject -> { vm.initPeer(receiver, descriptor, args); null }
            // A `super` call from an interpreted override into a real supertype: route to the peer, using the
            // `super$` trampoline when the interpreted class overrides the method (so the override is skipped).
            cls == null && receiver is VmObject -> {
                val overridden = vm.findInHierarchy(receiver.vmClass, name, descriptor) != null
                val target = if (overridden) "super\$$name" else name
                vm.bridgeVirtual(vm.peerOf(receiver), target, descriptor, args)
            }
            cls == null -> vm.bridgeVirtual(receiver!!, name, descriptor, args)
            else -> {
                val method = cls.declaredMethod(name, descriptor)
                    ?: vm.findInHierarchy(cls, name, descriptor)?.second
                when {
                    method != null -> execute(cls, method, receiver, args)
                    // The owner is interpreted but nothing in its interpreted chain declares the method: a
                    // `super` call whose target is on a real supertype below the chain. Route it to the peer's
                    // real super method (the `super$` trampoline when the interpreted class overrides it).
                    receiver is VmObject -> {
                        val overridden = vm.findInHierarchy(receiver.vmClass, name, descriptor) != null
                        vm.bridgeVirtual(vm.peerOf(receiver), if (overridden) "super\$$name" else name, descriptor, args)
                    }
                    else -> throw VmUnsupportedException("no method $owner.$name$descriptor")
                }
            }
        }
    }

    /** Invoke an instance method by virtual dispatch on the receiver's runtime type, with boxed arguments. */
    private fun dispatchVirtual(name: String, descriptor: String, rawReceiver: Any?, rawArgs: List<Any?>): Any? {
        val receiver = deref(rawReceiver) ?: throwReal(NullPointerException("invoke $name on null"))
        val args = rawArgs.map { deref(it) }
        // `Object.wait/notify/notifyAll` route to the interpreter's monitor for the receiver, so they pair with
        // the `MONITORENTER`/`synchronized` the interpreter ran on the same object (an interpreted instance's
        // real peer has a different monitor). These are final on Object, so an interpreted override never
        // pre-empts them; a same-named method with a different descriptor is not one of them.
        if (isObjectMonitorMethod(name, descriptor)) { runObjectMonitorMethod(name, receiver, args); return null }
        return when (receiver) {
            is VmLambda ->
                if (name == receiver.samName) receiver.invokeSam(args)
                else throw VmUnsupportedException("method $name on a functional-interface value is not supported")
            is VmObject -> {
                val found = vm.findInHierarchy(receiver.vmClass, name, descriptor)
                // A method the interpreted class inherits from a real supertype (not overridden) runs on the peer.
                if (found == null) vm.bridgeVirtual(vm.peerOf(receiver), name, descriptor, args)
                else execute(found.first, found.second, receiver, args)
            }
            // An interpreted array (`VmArray`) has no real class: `clone()` copies it; other Object methods run
            // on its real mirror. (Java arrays are Cloneable, but VmArray is not, so a bridged clone would fail.)
            is VmArray -> if (name == "clone") receiver.shallowCopy() else vm.bridgeVirtual(vm.toReal(receiver)!!, name, descriptor, args)
            else -> vm.bridgeVirtual(receiver, name, descriptor, args)
        }
    }

    /** Whether [name]/[descriptor] is one of `Object`'s monitor methods (exact descriptors, so an unrelated
     *  same-named method is not caught). */
    private fun isObjectMonitorMethod(name: String, descriptor: String): Boolean = when (name) {
        "notify", "notifyAll" -> descriptor == "()V"
        "wait" -> descriptor == "()V" || descriptor == "(J)V" || descriptor == "(JI)V"
        else -> false
    }

    /** Run `wait`/`notify`/`notifyAll` against the interpreter's monitor for [receiver]. `wait(J[I])` uses the
     *  millisecond argument (nanos are ignored, as the JVM effectively does for sub-millisecond waits). */
    private fun runObjectMonitorMethod(name: String, receiver: Any?, args: List<Any?>) {
        val monitor = vm.monitorFor(receiver)
        when (name) {
            "notify" -> monitor.notifyOne()
            "notifyAll" -> monitor.notifyEveryone()
            "wait" -> monitor.await(if (args.isEmpty()) 0L else (args[0] as Long))
        }
    }

    /** Resolve a bridged-object placeholder to the real object its constructor produced; identity otherwise. */
    private fun deref(v: Any?): Any? =
        if (v is Uninitialized) v.real ?: throw VmUnsupportedException("use of an uninitialized ${v.type} before its constructor ran")
        else v

    // ---- invokedynamic ----------------------------------------------------------------------------

    /** Link an `invokedynamic` call site. The bootstrap methods javac and kotlinc emit are supported:
     *  `LambdaMetafactory` (lambdas and method references), `StringConcatFactory` (string concatenation), and
     *  `ObjectMethods` (a record's generated `toString`/`equals`/`hashCode`). */
    private fun invokeDynamic(insn: InvokeDynamicInsnNode, frame: Frame) {
        val bsm = insn.bsm
        when (bsm.owner) {
            "java/lang/invoke/LambdaMetafactory" -> makeLambda(insn, frame)
            "java/lang/invoke/StringConcatFactory" -> frame.pushRef(makeConcat(insn, frame))
            "java/lang/runtime/ObjectMethods" -> recordMethod(insn, frame)
            else -> throw VmUnsupportedException("unsupported invokedynamic bootstrap ${bsm.owner}.${bsm.name}")
        }
    }

    /** Pop the call site's dynamic arguments boxed, in left-to-right order. */
    private fun popDynamicArgs(insn: InvokeDynamicInsnNode, frame: Frame): Array<Any?> {
        val argTypes = Type.getArgumentTypes(insn.desc)
        val args = arrayOfNulls<Any?>(argTypes.size)
        for (i in argTypes.indices.reversed()) args[i] = frame.popByDesc(argTypes[i].descriptor)
        return args
    }

    /**
     * A record's generated `toString`/`equals`/`hashCode`, linked through `ObjectMethods`. The bootstrap
     * arguments are the record type, a `;`-separated component-name list, and one accessor method handle per
     * component; the component values are read by invoking those accessors on the record instance.
     */
    private fun recordMethod(insn: InvokeDynamicInsnNode, frame: Frame) {
        val args = popDynamicArgs(insn, frame).map { deref(it) }
        val recordType = insn.bsmArgs[0] as Type
        val componentNames = (insn.bsmArgs[1] as String).split(";").filter { it.isNotEmpty() }
        val accessors = insn.bsmArgs.drop(2).map { it as Handle }
        fun component(record: Any?, i: Int) = invokeHandle(handleRef(accessors[i]), listOf(record))
        when (insn.name) {
            "toString" -> {
                val record = args[0]
                val simpleName = recordType.internalName.substringAfterLast('/').substringAfterLast('$')
                val body = componentNames.indices.joinToString(", ") { "${componentNames[it]}=${component(record, it)}" }
                frame.pushRef("$simpleName[$body]")
            }
            "equals" -> {
                val a = args[0]; val b = args[1]
                val equal = b is VmObject && a is VmObject && a.vmClass == b.vmClass &&
                    componentNames.indices.all { component(a, it) == component(b, it) }
                frame.pushI(if (equal) 1 else 0)
            }
            "hashCode" -> {
                val record = args[0]
                var hash = 0
                for (i in componentNames.indices) hash = hash * 31 + (component(record, i)?.hashCode() ?: 0)
                frame.pushI(hash)
            }
            else -> throw VmUnsupportedException("unsupported record method ${insn.name}")
        }
    }

    private fun handleRef(h: Handle) = MethodHandleRef(h.tag, h.owner, h.name, h.desc, h.isInterface)

    /**
     * Build a [VmLambda] from a `LambdaMetafactory` call site. The call-site descriptor's arguments are the
     * captured values, and its return type is the functional interface. The first two bootstrap arguments are
     * the erased sam method type and the implementation method handle (later bootstrap arguments, present for
     * `altMetafactory`, are not used).
     */
    private fun makeLambda(insn: InvokeDynamicInsnNode, frame: Frame) {
        val captured = popDynamicArgs(insn, frame)
        val interfaceType = Type.getReturnType(insn.desc).internalName
        val samType = insn.bsmArgs[0] as Type
        val impl = handleRef(insn.bsmArgs[1] as Handle)
        frame.pushRef(VmLambda(interfaceType, insn.name, samType.descriptor, impl, captured.asList()) { lambda, samArgs ->
            invokeHandle(lambda.impl, lambda.captured + samArgs)
        })
    }

    /**
     * Build the string a `StringConcatFactory` call site produces. For `makeConcatWithConstants` the first
     * bootstrap argument is a recipe: `0x01` consumes the next dynamic argument, `0x02` consumes the next
     * bootstrap constant, and any other character is literal. `makeConcat` has no recipe and concatenates the
     * dynamic arguments in order.
     */
    private fun makeConcat(insn: InvokeDynamicInsnNode, frame: Frame): String {
        val argTypes = Type.getArgumentTypes(insn.desc)
        val args = arrayOfNulls<Any?>(argTypes.size)
        for (i in argTypes.indices.reversed()) args[i] = deref(frame.popByDesc(argTypes[i].descriptor))
        val out = StringBuilder()
        if (insn.name == "makeConcatWithConstants") {
            val recipe = insn.bsmArgs[0] as String
            var arg = 0
            var const = 1
            for (ch in recipe) when (ch) {
                TAG_ARG -> { out.append(format(args[arg], argTypes[arg])); arg++ }
                TAG_CONST -> { out.append(insn.bsmArgs[const]); const++ }
                else -> out.append(ch)
            }
        } else {
            for (i in args.indices) out.append(format(args[i], argTypes[i]))
        }
        return out.toString()
    }

    /** Render a concatenation argument using its call-site type, so a char appends its character. */
    private fun format(value: Any?, type: Type): String = when (type.descriptor) {
        "C" -> (value as Int).toChar().toString()
        "Z" -> ((value as Int) != 0).toString()
        else -> value?.toString() ?: "null"
    }

    /** Invoke the target of a method handle with the full argument list (captured values then call values). A
     *  field-kind handle (used by a record's `ObjectMethods` component accessors) reads or writes the field. */
    private fun invokeHandle(ref: MethodHandleRef, allArgs: List<Any?>): Any? = when (ref.tag) {
        H_INVOKESTATIC -> dispatchStatic(ref.owner, ref.name, ref.descriptor, allArgs)
        H_INVOKEVIRTUAL, H_INVOKEINTERFACE -> dispatchVirtual(ref.name, ref.descriptor, allArgs.first(), allArgs.drop(1))
        H_INVOKESPECIAL -> dispatchSpecial(ref.owner, ref.name, ref.descriptor, allArgs.first(), allArgs.drop(1))
        H_NEWINVOKESPECIAL -> constructVia(ref.owner, ref.descriptor, allArgs)
        H_GETFIELD -> fieldGet(allArgs.first(), ref.name, ref.descriptor)
        H_GETSTATIC -> staticGet(ref.owner, ref.name, ref.descriptor)
        H_PUTFIELD -> { fieldPut(allArgs[0], ref.name, ref.descriptor, allArgs[1]); null }
        H_PUTSTATIC -> { staticPut(ref.owner, ref.name, ref.descriptor, allArgs.first()); null }
        else -> throw VmUnsupportedException("method-handle kind ${ref.tag} is not supported")
    }

    // ---- object and array helpers -----------------------------------------------------------------

    /** Allocate the value a `new` instruction pushes: a [VmObject] for an interpreted class, or an
     *  [Uninitialized] placeholder for a bridged class whose real object its `<init>` produces. */
    private fun instantiate(internal: String): Any {
        val cls = vm.resolve(internal) ?: return Uninitialized(internal)
        vm.ensureInitialized(cls)
        return vm.newInstance(cls)
    }

    /** Construct an object for a constructor reference: an interpreted class runs its `<init>`, a bridged one
     *  is built by the [NativeBridge] (all arguments are present at once, so no uninitialized instance escapes). */
    private fun constructVia(owner: String, descriptor: String, args: List<Any?>): Any {
        val cls = vm.resolve(owner)
            ?: return vm.bridgeConstruct(owner, descriptor, args)
                ?: throw VmUnsupportedException("construction of $owner returned no instance")
        vm.ensureInitialized(cls)
        val obj = vm.newInstance(cls)
        val ctor = cls.declaredMethod("<init>", descriptor)
            ?: throw VmUnsupportedException("no constructor $owner.<init>$descriptor")
        execute(cls, ctor, obj, args)
        return obj
    }

    private fun castOk(v: Any?, target: String): Boolean = v == null || instanceOf(v, target)

    private fun instanceOf(v: Any?, target: String): Boolean = when (v) {
        null -> false
        is VmObject -> {
            val t = if (target.startsWith("L")) target.substring(1).trimEnd(';') else target
            // Match the interpreted hierarchy by name, or a real supertype (a real interface or class reached
            // through a bridged super) by assignability.
            vm.isSubtype(v.vmClass, t) || vm.isRealInstance(v.vmClass, t)
        }
        is VmArray -> target.startsWith("[")
        is VmLambda -> target == "java/lang/Object" || target == v.interfaceType
        // A real array cast to an array target: trust it. The element type may be an interpreted class (a
        // bridged call returning `T[]` for an interpreted `T`, e.g. Spannable.getSpans), which the real
        // classloader can't resolve, so an exact check would wrongly reject a compiler-emitted cast.
        else -> if (target.startsWith("[") && v.javaClass.isArray) true
        else runCatching { Class.forName(target.replace('/', '.'), false, javaClass.classLoader).isInstance(v) }.getOrDefault(false)
    }

    /** The length of a [VmArray] or a real Java array (returned from a bridge call). */
    private fun arrayLength(a: Any?): Int = when (a) {
        null -> throwReal(NullPointerException("arraylength on null"))
        is VmArray -> a.length
        else -> java.lang.reflect.Array.getLength(a)
    }

    /** Read an element of a [VmArray] or a real Java array, converting a real element to the interpreter's form
     *  by the array's component type (a primitive component takes the computational form). */
    private fun arrayGet(a: Any?, i: Int): Any? = when (a) {
        null -> throwReal(NullPointerException("array access on null"))
        is VmArray -> { checkIndex(i, a.length); a.data[i] }
        else -> {
            checkIndex(i, java.lang.reflect.Array.getLength(a))
            val v = java.lang.reflect.Array.get(a, i)
            if (a.javaClass.componentType.isPrimitive) Marshalling.realPrimToVm(v) else Marshalling.realToVm(v)
        }
    }

    /** Write an element into a [VmArray] or a real Java array, converting to the real component type. */
    private fun arraySet(a: Any?, i: Int, v: Any?) = when (a) {
        null -> throwReal(NullPointerException("array access on null"))
        is VmArray -> { checkIndex(i, a.length); a.data[i] = v }
        else -> { checkIndex(i, java.lang.reflect.Array.getLength(a)); java.lang.reflect.Array.set(a, i, realElement(v, a.javaClass.componentType)) }
    }

    private fun checkIndex(i: Int, length: Int) {
        if (i < 0 || i >= length) throwReal(ArrayIndexOutOfBoundsException("Index $i out of bounds for length $length"))
    }

    /** Convert an interpreter value to a real array element of [componentType]. */
    private fun realElement(v: Any?, componentType: Class<*>): Any? = when (componentType) {
        Boolean::class.javaPrimitiveType -> (v as Int) != 0
        Char::class.javaPrimitiveType -> (v as Int).toChar()
        Byte::class.javaPrimitiveType -> (v as Int).toByte()
        Short::class.javaPrimitiveType -> (v as Int).toShort()
        else -> if (componentType.isPrimitive) v else vm.toReal(v)
    }

    private fun primitiveArrayDesc(operand: Int): String = when (operand) {
        T_BOOLEAN -> "Z"; T_CHAR -> "C"; T_FLOAT -> "F"; T_DOUBLE -> "D"
        T_BYTE -> "B"; T_SHORT -> "S"; T_INT -> "I"; T_LONG -> "J"
        else -> error("bad NEWARRAY operand $operand")
    }

    /** The element descriptor for `anewarray` of internal type [desc] (already an array descriptor when nested). */
    private fun elementDescOf(desc: String): String = if (desc.startsWith("[")) desc else "L$desc;"

    private fun multiArray(insn: MultiANewArrayInsnNode, frame: Frame): VmArray {
        val dims = IntArray(insn.dims)
        for (i in insn.dims - 1 downTo 0) dims[i] = frame.popI()
        return buildMultiArray(insn.desc, dims, 0)
    }

    private fun buildMultiArray(arrayDesc: String, dims: IntArray, depth: Int): VmArray {
        val elementDesc = arrayDesc.substring(1) // strip one leading '['
        val arr = VmArray.of(elementDesc, dims[depth])
        if (depth + 1 < dims.size) for (i in 0 until dims[depth]) arr.data[i] = buildMultiArray(elementDesc, dims, depth + 1)
        return arr
    }
}
