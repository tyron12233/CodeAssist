package dev.ide.build.jvm.run

import java.lang.ref.WeakReference

/**
 * The top-level windows an interpreted program put on screen, tracked so a GUI run ends when the program's
 * last window closes rather than the moment `main` returns.
 *
 * A Swing/AWT program's `main` returns as soon as it calls `setVisible(true)`; the program then lives on the
 * AWT event-dispatch thread. That thread is NOT in the run's own `ThreadGroup` whenever anything touched AWT
 * before the run did (in the desktop IDE, always: Compose for Desktop and `AwtThreadGuard` bring the EDT up at
 * startup), so [VmProgramInterpreter]'s thread-based wait sees nothing to wait for and the run reports
 * finished with the window still on screen. Everything the run tears down at that point (the classpath jars,
 * the redirected `System.out`) is torn down under a program that is still running.
 *
 * Windows are recorded from the two places one can be created: [dev.ide.jvm.PeerFactory.createPeer], for a
 * program's own `class MyFrame extends JFrame`, and [RunBridge.construct], for a plain `new JFrame()`. Held
 * weakly, so a window the program drops is collectable.
 *
 * Deliberately AWT-free: [isAwtWindow] walks class names instead of referencing `java.awt`, and the calls go
 * through reflection, because this module is also dexed for the device, where no such class exists (nothing is
 * ever recorded there and every operation is a no-op).
 */
internal class ProgramWindows(
    /** Which constructed objects count as windows. Overridden in tests, where no display exists to make a
     *  real `java.awt.Window` with. */
    private val isWindowType: (Class<*>) -> Boolean = ::isAwtWindow,
) {
    private val tracked = ArrayList<WeakReference<Any>>()

    /** Record [value] if it is a top-level window; anything else is ignored. */
    fun record(value: Any?) {
        if (value == null || !isWindowType(value.javaClass)) return
        synchronized(tracked) { tracked.add(WeakReference(value)) }
    }

    /**
     * How many of the program's windows are still realized. `isDisplayable` (not `isVisible`) is the test
     * because it is the one AWT itself uses to decide whether to keep the event-dispatch thread alive: a
     * `dispose`d window is not displayable, while one merely hidden with `setVisible(false)` still is and can
     * be shown again.
     */
    fun liveCount(): Int = snapshot().count { call(it, "isDisplayable") == true }

    /** Dispose every tracked window, so Stop (and the end of the run) really ends the program. */
    fun disposeAll() {
        snapshot().forEach { call(it, "dispose") }
    }

    /** The windows still reachable, dropping references the collector has cleared as it goes so a long-lived
     *  program that opens and closes many dialogs does not accumulate dead entries. */
    private fun snapshot(): List<Any> = synchronized(tracked) {
        val live = tracked.mapNotNull { it.get() }
        if (live.size != tracked.size) {
            tracked.clear()
            live.forEach { tracked.add(WeakReference(it)) }
        }
        live
    }

    /** Invoke a public no-argument method by name, or null if it is missing or throws (a window disposed
     *  concurrently, an AWT internal error): liveness must never be the thing that fails a run. */
    private fun call(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()
}

/** Whether [cls] is a `java.awt.Window`, decided by walking superclass NAMES so this module never references
 *  `java.awt` (absent on the device). A generated peer for `class MyFrame extends JFrame` matches through its
 *  real superclass chain, exactly as a plain `JFrame` does. */
internal fun isAwtWindow(cls: Class<*>): Boolean {
    var c: Class<*>? = cls
    while (c != null) {
        if (c.name == "java.awt.Window") return true
        c = c.superclass
    }
    return false
}
