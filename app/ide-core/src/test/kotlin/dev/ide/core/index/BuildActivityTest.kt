package dev.ide.core.index

import dev.ide.platform.BuildActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Background work waits while a build compiles, resumes when it ends, and never waits past the cap. */
class BuildActivityTest {
    @AfterTest fun reset() = BuildActivity.set(false)

    @Test
    fun waitsForTheBuildThenResumes() = runBlocking {
        BuildActivity.set(true)
        val waiter = async { BuildActivity.awaitIdle(maxWaitMs = 10_000); true }
        delay(100)
        assertFalse(waiter.isCompleted, "still waiting while the build runs")
        BuildActivity.set(false)
        assertTrue(waiter.await())
    }

    @Test
    fun aBuildThatNeverEndsOnlyDelaysTheWork() = runBlocking {
        BuildActivity.set(true)
        val t0 = System.nanoTime()
        BuildActivity.awaitIdle(maxWaitMs = 200)
        assertTrue((System.nanoTime() - t0) / 1_000_000 in 150..5_000)
    }
}
