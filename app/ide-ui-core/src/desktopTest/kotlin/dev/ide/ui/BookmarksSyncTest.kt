package dev.ide.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertTrue

/** The session effects start on the home screen, before any project is open: bookmark syncing must wait, not throw. */
class BookmarksSyncTest {
    @Test
    fun noOpenProjectDoesNotCrashTheSessionEffects() {
        val backend = object : StubBackend() {
            override val project: dev.ide.ui.backend.ProjectInfo get() = throw IllegalStateException("No project is open")
        }
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        var failure: Throwable? = null
        runBlocking {
            val job = launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> failure = e }) {
                runCatching { state.runSessionEffects() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failure = it }
            }
            repeat(20) { yield() }
            job.cancelAndJoin()
        }
        assertTrue(failure == null || "bookmark" !in failure!!.stackTraceToString().lowercase(), "bookmark sync threw: $failure")
    }
}
