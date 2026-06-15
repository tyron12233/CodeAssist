package dev.ide.platform.impl

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelReadWriteLockTest {

    @Test
    fun readersRunConcurrently() {
        val lock = ModelReadWriteLock()
        val n = 4
        // A barrier of size n only trips if all n readers are inside their read action at once;
        // if the lock serialized readers it would never trip and await() would time out -> failure.
        val barrier = CyclicBarrier(n)
        val current = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val threads = (1..n).map {
            thread {
                lock.read {
                    val c = current.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, c) }
                    barrier.await(3, TimeUnit.SECONDS)
                    current.decrementAndGet()
                }
            }
        }
        threads.forEach { it.join(5_000) }

        assertEquals(n, maxConcurrent.get(), "all readers should hold the read lock simultaneously")
    }

    @Test
    fun writerExcludesReaders() {
        val lock = ModelReadWriteLock()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val writerInside = CountDownLatch(1)

        val writer = thread {
            lock.write {
                log.add("w-start")
                writerInside.countDown()
                Thread.sleep(150)
                log.add("w-end")
            }
        }
        writerInside.await()
        val reader = thread {
            lock.read { log.add("r") }
        }

        writer.join(5_000)
        reader.join(5_000)

        // The reader cannot enter until the writer has fully released the lock.
        assertTrue(log.indexOf("w-end") < log.indexOf("r"), "reader entered before writer released: $log")
    }

    @Test
    fun writerMayReadReentrantly() {
        val lock = ModelReadWriteLock()
        val value = lock.write {
            // A thread holding the write lock can take a read action without deadlocking.
            lock.read { 99 }
        }
        assertEquals(99, value)
    }

    @Test
    fun writeInsideReadIsRejected() {
        val lock = ModelReadWriteLock()
        assertFailsWith<IllegalArgumentException> {
            lock.read { lock.write { } }
        }
    }
}
