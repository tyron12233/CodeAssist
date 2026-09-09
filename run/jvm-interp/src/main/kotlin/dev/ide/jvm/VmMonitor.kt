package dev.ide.jvm

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A JVM object monitor for the interpreter: reentrant mutual exclusion (a `synchronized` block's
 * `MONITORENTER`/`MONITOREXIT`, or a `synchronized` method) plus the `Object.wait`/`notify`/`notifyAll`
 * condition. One is attached lazily to each lockable the interpreter observes — a [VmObject], a [VmClass] (for
 * a static `synchronized` method or `synchronized(T.class)`), a [VmArray], or a bridged real object — through
 * [Vm.monitorFor], so interpreted synchronization runs against a consistent lock regardless of the operand's
 * kind.
 *
 * Backed by a [ReentrantLock] and a single condition. `Condition.await` fully releases the reentrant hold count
 * and restores it on wake-up, so a `wait` inside nested `synchronized` blocks behaves like the JVM's. An
 * operation performed without owning the monitor raises a real `IllegalMonitorStateException` (matchable by an
 * interpreted `catch`), and an interrupt while waiting raises a real `InterruptedException` — which is also how
 * run cancellation (`Thread.interrupt`) breaks a thread blocked in `wait`.
 */
internal class VmMonitor {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    fun enter() = lock.lock()

    fun exit() {
        if (!lock.isHeldByCurrentThread) illegalState("monitor exit")
        lock.unlock()
    }

    /** `Object.wait`: atomically release the monitor and block until signalled, interrupted, or [millis]
     *  elapses (0 = no timeout), then reacquire it with the prior hold count. */
    fun await(millis: Long) {
        if (!lock.isHeldByCurrentThread) illegalState("wait")
        try {
            if (millis <= 0L) cond.await() else cond.await(millis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw VmException(e)
        }
    }

    /** `Object.notify`. Signals one waiter; the JVM's choice of which is unspecified, so signalling any is faithful. */
    fun notifyOne() {
        if (!lock.isHeldByCurrentThread) illegalState("notify")
        cond.signal()
    }

    /** `Object.notifyAll`. */
    fun notifyEveryone() {
        if (!lock.isHeldByCurrentThread) illegalState("notifyAll")
        cond.signalAll()
    }

    private fun illegalState(op: String): Nothing =
        throw VmException(IllegalMonitorStateException("$op without owning the monitor"))
}
