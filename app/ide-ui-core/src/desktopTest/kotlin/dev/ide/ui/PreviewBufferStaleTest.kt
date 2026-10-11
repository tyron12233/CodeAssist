package dev.ide.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [whileBufferCurrent]: a preview pass stands down while the pane's buffer is outdated and restarts after. */
class PreviewBufferStaleTest {

    private fun set(state: androidx.compose.runtime.MutableState<Boolean>, value: Boolean) {
        state.value = value
        // No Recomposer here to deliver the write to snapshotFlow; send it by hand.
        Snapshot.sendApplyNotifications()
    }

    private suspend fun until(cond: () -> Boolean) = withTimeout(2_000) { while (!cond()) delay(5) }

    @Test
    fun aStalePassIsCancelledAndRunsAgainOnceCurrent() {
        runBlocking {
            val stale = mutableStateOf(false)
            val starts = AtomicInteger()
            val finish = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                whileBufferCurrent(stale) { starts.incrementAndGet(); finish.await() }
            }
            until { starts.get() == 1 }

            set(stale, true)
            delay(100)
            assertEquals(1, starts.get(), "nothing may start while the buffer is outdated")
            assertTrue(job.isActive, "a stand-down is not the end of the pass")

            set(stale, false)
            until { starts.get() == 2 }
            finish.complete(Unit)
            withTimeout(2_000) { job.join() }
            assertEquals(2, starts.get())
        }
    }

    @Test
    fun aPassThatStartsStaleWaitsForTheBufferFirst() {
        runBlocking {
            val stale = mutableStateOf(true)
            val starts = AtomicInteger()
            val job = launch(Dispatchers.Default) { whileBufferCurrent(stale) { starts.incrementAndGet() } }
            delay(100)
            assertEquals(0, starts.get())
            set(stale, false)
            withTimeout(2_000) { job.join() }
            assertEquals(1, starts.get())
        }
    }

    @Test
    fun cancellingTheCallerEndsIt() {
        runBlocking {
            val stale = mutableStateOf(false)
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                whileBufferCurrent(stale) { started.complete(Unit); CompletableDeferred<Unit>().await() }
            }
            started.await()
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
        }
    }
}
