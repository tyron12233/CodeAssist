package dev.ide.vm.jdk

import dev.ide.vm.*

/**
 * The UI thread, as desktop libraries reach it: `SwingUtilities.invokeLater`, `EventQueue.invokeLater` and
 * `javax.swing.Timer`. skiko's `Dispatchers.Main` is built on exactly these three, so with them a coroutine
 * Compose launches on the main dispatcher lands in the VM's own queue ([Vm.runPendingTasks]) instead of on
 * an AWT event thread, which no host here has. Nothing else of Swing or AWT exists.
 */
internal fun ClassDefs.registerEventLoop() {
    define("javax/swing/SwingUtilities") {
        static("invokeLater", "(Ljava/lang/Runnable;)V") { vm.post(r(0)!!, 0L) }
        static("invokeAndWait", "(Ljava/lang/Runnable;)V") { vm.callVirtual(r(0), "run", "()V") }
        static("isEventDispatchThread", "()Z") { ret(true) }
    }
    define("java/awt/EventQueue") {
        static("invokeLater", "(Ljava/lang/Runnable;)V") { vm.post(r(0)!!, 0L) }
        static("invokeAndWait", "(Ljava/lang/Runnable;)V") { vm.callVirtual(r(0), "run", "()V") }
        static("isDispatchThread", "()Z") { ret(true) }
    }
    define("java/awt/event/ActionListener") { asInterface(); implements("java/util/EventListener") }
    define("java/util/EventObject") {
        implements("java/io/Serializable")
        field("source", "Ljava/lang/Object;")
        ctor("(Ljava/lang/Object;)V") { self().refs[0] = r(1) }
        method("getSource", "()Ljava/lang/Object;") { retRef(self().refs[0]) }
    }
    define("java/awt/AWTEvent") { superName = "java/util/EventObject" }
    define("java/awt/event/ActionEvent") {
        superName = "java/awt/AWTEvent"
        ctor("(Ljava/lang/Object;ILjava/lang/String;)V") { self().refs[0] = r(1) }
    }
    // Desktop Compose's PointerIcon wraps an AWT cursor as data; nothing draws it here.
    define("java/awt/Cursor") {
        implements("java/io/Serializable")
        field("type", "I")
        ctor("(I)V") { self().prims[0] = i(1).toLong() }
        method("getType", "()I") { ret(self().prims[0].toInt()) }
        method("getName", "()Ljava/lang/String;") { retRef("cursor-" + self().prims[0]) }
        method("equals", "(Ljava/lang/Object;)Z") { val o = r(1); ret(o is VmObject && o.cls === self().cls && o.prims[0] == self().prims[0]) }
        method("hashCode", "()I") { ret(self().prims[0].toInt()) }
        static("getPredefinedCursor", "(I)Ljava/awt/Cursor;") { retRef(vm.newVmObject("java/awt/Cursor").also { it.prims[0] = i(0).toLong() }) }
        static("getDefaultCursor", "()Ljava/awt/Cursor;") { retRef(vm.newVmObject("java/awt/Cursor")) }
    }

    define("javax/swing/Timer") {
        implements("java/io/Serializable")
        fun Call.timer(): TimerState = self().native as TimerState
        ctor("(ILjava/awt/event/ActionListener;)V") { self().native = TimerState(i(1), r(2)) }
        method("setRepeats", "(Z)V") { timer().repeats = z(1) }
        method("isRepeats", "()Z") { ret(timer().repeats) }
        method("setInitialDelay", "(I)V") { timer().delay = i(1) }
        method("setDelay", "(I)V") { timer().delay = i(1) }
        method("getDelay", "()I") { ret(timer().delay) }
        method("isRunning", "()Z") { ret(timer().generation > 0) }
        method("start", "()V") { vm.startTimer(self()) }
        method("restart", "()V") { vm.startTimer(self()) }
        method("stop", "()V") { timer().generation = 0 }
    }
}

internal class TimerState(var delay: Int, val listener: Any?) {
    var repeats = true
    /** Bumped by each start; a queued firing from an earlier start (or after stop, 0) is ignored. */
    var generation = 0
}

private fun Vm.startTimer(timer: VmObject) {
    val state = timer.native as TimerState
    val generation = ++timerGeneration
    state.generation = generation
    schedule(state.delay.toLong()) { fireTimer(timer, generation) }
}

private fun Vm.fireTimer(timer: VmObject, generation: Int) {
    val state = timer.native as TimerState
    if (state.generation != generation) return
    val event = newVmObject("java/awt/event/ActionEvent").also { it.refs[0] = timer }
    if (!state.repeats) state.generation = 0
    callVirtual(state.listener, "actionPerformed", "(Ljava/awt/event/ActionEvent;)V", event)
    if (state.repeats && state.generation == generation) schedule(state.delay.toLong()) { fireTimer(timer, generation) }
}
