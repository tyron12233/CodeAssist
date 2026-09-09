package dev.ide.jvm

import dev.ide.jvm.fixtures.Threading
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives the multi-threaded VM against the [Threading] fixtures with the **real** invocation as the oracle:
 * each fixture is deterministic when synchronization is correct, so a broken monitor / wait-notify / class-init
 * / thread-spawn would make the interpreted result diverge (a lost update, a wrong sum, or a deadlock) rather
 * than match. A per-class [Timeout] turns a would-be deadlock into a failure instead of a hung suite. The
 * fixtures start bridged real `Thread`s whose `run` re-enters the interpreter on that host thread.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class ThreadingTest {

    private val vm = Vm()
    private val THREADING = "dev/ide/jvm/fixtures/Threading"

    private fun call(name: String, desc: String, vararg args: Any?): Any? =
        vm.invokeStatic(THREADING, name, desc, args.toList())

    @Test fun synchronizedBlockIsMutuallyExclusive() {
        // 4 threads × 500 increments under a synchronized(lock) block → exactly 2000 iff MONITORENTER/EXIT lock.
        assertEquals(Threading.syncBlockCounter(4, 500), call("syncBlockCounter", "(II)I", 4, 500))
    }

    @Test fun synchronizedMethodIsMutuallyExclusive() {
        // Same contention through an ACC_SYNCHRONIZED method on a shared interpreted object.
        assertEquals(Threading.syncMethodCounter(4, 500), call("syncMethodCounter", "(II)I", 4, 500))
    }

    @Test fun startAndJoinReturnsWorkerResult() {
        // The pattern that used to deadlock: main starts a worker and joins it, then reads its result.
        assertEquals(Threading.joinResult(10_000), call("joinResult", "(I)J", 10_000))
    }

    @Test fun waitNotifyProducerConsumer() {
        // wait/notifyAll on an interpreted monitor across a producer and a consumer thread.
        assertEquals(Threading.produceConsume(200), call("produceConsume", "(I)J", 200))
    }

    @Test fun bridgedAtomicUnderParallelism() {
        // The real java.util.concurrent primitive works unchanged through the bridge under genuine parallelism.
        assertEquals(Threading.atomicCounter(4, 1000), call("atomicCounter", "(II)I", 4, 1000))
    }

    @Test fun requestCancelUnwindsComputeLoopOnWorker() {
        val error = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try {
                vm.invokeStatic(THREADING, "spinForever", "()J", emptyList())
            } catch (t: Throwable) {
                error[0] = t
            }
        }
        worker.start()
        Thread.sleep(200) // let it enter the loop
        vm.requestCancel()
        worker.join(5_000)
        assertFalse(worker.isAlive, "worker did not unwind after requestCancel")
        assertTrue(error[0] is VmInterruptedException, "expected VmInterruptedException, got ${error[0]}")
    }

    @Test fun workerObservesHostInterruption() {
        val result = arrayOfNulls<Any>(1)
        val worker = Thread { result[0] = vm.invokeStatic(THREADING, "spinUntilInterrupted", "()J", emptyList()) }
        worker.start()
        Thread.sleep(200)
        worker.interrupt()
        worker.join(5_000)
        assertFalse(worker.isAlive, "worker did not stop after interrupt")
        assertTrue((result[0] as Long) >= 0)
    }

    // ---- limit fixes -----------------------------------------------------------------------------

    /** Limit 3: `synchronized(T.class)` (a class-literal lock) and a `static synchronized` method on T must
     *  lock the SAME monitor. The class literal of an interpreted type is its reflection Class; assert it
     *  resolves to the same monitor object as the type's VmClass. */
    @Test fun classLiteralAndStaticSyncShareOneMonitor() {
        val vmClass = vm.resolve(THREADING)!!
        val reflectionClass = vm.classForInterpreted(THREADING)!!
        assertSame(vm.monitorFor(vmClass), vm.monitorFor(reflectionClass))
    }

    /** Limit 2: a volatile field's slot is an AtomicReference holder (real volatile semantics), a plain field's
     *  is not — the selective wiring. */
    @Test fun volatileFieldsAreBackedByAtomicReference() {
        val holder = vm.construct("$THREADING\$Holder", "()V") as VmObject
        assertTrue(holder.fields["volatileVal"] is AtomicReference<*>, "volatile field should be an AtomicReference holder")
        assertFalse(holder.fields["plainVal"] is AtomicReference<*>, "plain field should not be wrapped")
        // The static volatile unwraps through the access API (not returned as the raw holder).
        assertEquals(0L, vm.interpretedStaticValue("dev.ide.jvm.fixtures.Threading\$Holder", "staticVolatileVal"))
    }

    /** Limit 2: volatile + plain + static-volatile read/write round-trip to the right values. */
    @Test fun volatileRoundTrip() {
        assertEquals(Threading.volatileRoundTrip(100), call("volatileRoundTrip", "(J)J", 100L))
    }

    /** Limit 2: a worker spinning on a volatile flag observes the main thread's write and terminates (a hang
     *  would trip the class timeout). */
    @Test fun volatileStopFlagIsSeenAcrossThreads() {
        assertTrue((call("stopFlagLoop", "()J") as Long) > 0, "worker should iterate then observe the stop flag")
    }

    /** Limit 1: a spawned thread given a large stack (via the Vm config) reaches a far deeper interpreted
     *  recursion before overflowing than one on the host default. Self-calibrating, so it doesn't depend on the
     *  platform's absolute default. */
    @Test fun spawnedThreadUsesConfiguredStackSize() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> } // the probe overflows on purpose; swallow the noise
        try {
            val defaultDepth = Vm().invokeStatic(THREADING, "maxRecursionDepthOnThread", "()I", emptyList()) as Int
            val bigDepth = Vm(threadStackSize = 32L * 1024 * 1024)
                .invokeStatic(THREADING, "maxRecursionDepthOnThread", "()I", emptyList()) as Int
            assertTrue(bigDepth > defaultDepth * 2, "big-stack thread reached depth $bigDepth vs default $defaultDepth")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
