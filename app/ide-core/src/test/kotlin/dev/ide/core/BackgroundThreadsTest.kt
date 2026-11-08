package dev.ide.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundThreadsTest {

    private val original = BackgroundThreads.lowerCurrentThread

    @AfterTest
    fun restore() {
        BackgroundThreads.lowerCurrentThread = original
    }

    @Test
    fun everyPoolThreadLowersItselfBeforeRunningWork() {
        val lowered = Collections.synchronizedSet(HashSet<String>())
        BackgroundThreads.lowerCurrentThread = { lowered += Thread.currentThread().name }
        val pool = BackgroundThreads.pool("bg-test", 3)
        // Three tasks that wait for each other: they only finish if the pool really runs three threads at once.
        val started = CountDownLatch(3)
        val ran = Collections.synchronizedSet(HashSet<String>())
        repeat(3) {
            pool.execute {
                assertTrue(Thread.currentThread().name in lowered, "work ran before the thread lowered itself")
                ran += Thread.currentThread().name
                started.countDown()
                started.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the pool did not grow to three threads")
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(setOf("bg-test-1", "bg-test-2", "bg-test-3"), ran.toSet())
        assertTrue(Thread.currentThread().name !in lowered, "the caller's own thread was lowered")
    }

    @Test
    fun aFailingHookDoesNotKillTheWork() {
        BackgroundThreads.lowerCurrentThread = { error("no priority API here") }
        val pool = BackgroundThreads.single("bg-single")
        val name = pool.submit<String> { Thread.currentThread().name }.get(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals("bg-single", name)
    }
}
