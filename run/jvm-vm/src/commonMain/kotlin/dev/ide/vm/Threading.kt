package dev.ide.vm

/**
 * How a program that expects threads runs on the VM's one.
 *
 * Work a program hands another thread is queued on the VM's main queue ([Vm.runPendingTasks]) instead, and
 * wherever the one thread would BLOCK waiting for that work (parking, sleeping) it runs the queue instead.
 * So `withContext(Dispatchers.Default) { ... }` inside `runBlocking` finishes rather than parking forever,
 * and a coroutine sent to `Dispatchers.IO` runs rather than being dropped on a pool thread that never starts.
 * Nothing runs in parallel; what the program computes is what a sequential schedule of it computes.
 */
internal object Threading {
    private const val DISPATCHERS = "kotlinx/coroutines/Dispatchers"
    private const val DISPATCHER = "kotlinx/coroutines/CoroutineDispatcher"

    fun override(vm: Vm, owner: String, name: String, descriptor: String): Native? = when ("$owner.$name$descriptor") {
        // kotlinx.coroutines' thread pools become the VM's own dispatcher.
        "$DISPATCHERS.getDefault()L$DISPATCHER;",
        "$DISPATCHERS.getIO()L$DISPATCHER;" -> Native { call -> call.retRef(vm.queueDispatcher()) }
        // `delay` and `withTimeout` without a dispatcher of their own go to DefaultExecutor, a thread of its own.
        "kotlinx/coroutines/DelayKt.getDelay(Lkotlin/coroutines/CoroutineContext;)Lkotlinx/coroutines/Delay;" ->
            Native { call -> call.retRef(vm.queueDispatcher()) }
        else -> null
    }

    /**
     * A `CoroutineDispatcher` that runs every task on the VM's main queue, and a `Delay` that schedules on its
     * timers. Defined in the VM, extending the program's own `CoroutineDispatcher`, so it is whatever version
     * of kotlinx.coroutines the program has.
     */
    private fun Vm.queueDispatcher(): VmObject = singletons.getOrPut("\u0000queue-dispatcher") {
        val base = loadClass(DISPATCHER)
        ensureInitialized(base)
        val cls = VmClass(this, "dev/ide/vm/QueueDispatcher")
        cls.isNative = true
        cls.access = ACC_PUBLIC or ACC_FINAL
        cls.superClass = base
        cls.interfaces = arrayOf(loadClass("kotlinx/coroutines/Delay"))
        cls.primSlots = base.primSlots
        cls.refSlots = base.refSlots
        cls.initState = INIT_DONE
        fun native(name: String, descriptor: String, body: (Call) -> Unit) {
            val m = VmMethod(cls, name, descriptor, ACC_PUBLIC)
            m.native = Native(body)
            cls.methods[m.key] = m
        }
        native("dispatch", "(Lkotlin/coroutines/CoroutineContext;Ljava/lang/Runnable;)V") { call -> post(call.r(2)!!, 0L) }
        native("isDispatchNeeded", "(Lkotlin/coroutines/CoroutineContext;)Z") { call -> call.ret(true) }
        native("scheduleResumeAfterDelay", "(JLkotlinx/coroutines/CancellableContinuation;)V") { call ->
            val continuation = call.r(3)
            schedule(call.l(1)) { callVirtual(continuation, "resumeWith", "(Ljava/lang/Object;)V", unit()) }
        }
        native("invokeOnTimeout", "(JLjava/lang/Runnable;Lkotlin/coroutines/CoroutineContext;)Lkotlinx/coroutines/DisposableHandle;") { call ->
            val runnable = call.r(3)
            val handle = disposableHandle()
            schedule(call.l(1)) { if (handle.prims[0] == 0L) callVirtual(runnable, "run", "()V") }
            call.retRef(handle)
        }
        native("toString", "()Ljava/lang/String;") { call -> call.retRef("Dispatchers.VmQueue") }
        val o = VmObject(cls)
        callMethod(findMethod(base, "<init>()V") ?: throw VmUnsupportedException("$DISPATCHER has no no-arg constructor"), o)
        o
    }

    /** A `DisposableHandle` whose `dispose()` marks it (prim slot 0), which the timer checks before running. */
    private fun Vm.disposableHandle(): VmObject {
        val cls = proxyClasses.getOrPut("\u0000disposable") {
            val c = VmClass(this, "dev/ide/vm/QueueTimeout")
            c.isNative = true
            c.access = ACC_PUBLIC or ACC_FINAL
            c.superClass = objectClass
            c.interfaces = arrayOf(loadClass("kotlinx/coroutines/DisposableHandle"))
            c.primSlots = 1
            c.initState = INIT_DONE
            val m = VmMethod(c, "dispose", "()V", ACC_PUBLIC)
            m.native = Native { call -> (call.r(0) as VmObject).prims[0] = 1L }
            c.methods[m.key] = m
            c
        }
        return VmObject(cls)
    }

    private fun Vm.unit(): Any? {
        val unit = loadClass("kotlin/Unit")
        ensureInitialized(unit)
        return unit.staticRefs[unit.fields["INSTANCE:Lkotlin/Unit;"]!!.slot]
    }
}
