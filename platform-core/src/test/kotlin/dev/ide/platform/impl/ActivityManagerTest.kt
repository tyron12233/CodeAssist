package dev.ide.platform.impl

import dev.ide.platform.ActivitySpec
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Note: test methods use block bodies (`{ runBlocking { … } }`), not expression bodies. An expression
// body whose last statement returns a value (e.g. `assertFailsWith { … }`) gives the method a
// non-Unit return type, and JUnit Jupiter silently skips such methods during discovery.
class ActivityManagerTest {

    @Test
    fun runsBlockAndReturnsResult() {
        runBlocking {
            val platform = PlatformCore()
            try {
                val activity = platform.activityManager.launch(ActivitySpec("compute")) {
                    readAction { 6 * 7 }
                }
                assertEquals(42, activity.await())
            } finally {
                platform.dispose()
            }
        }
    }

    @Test
    fun reportsProgressToTheSink() {
        runBlocking {
            val reports = CopyOnWriteArrayList<Pair<Double, String?>>()
            val platform = PlatformCore(onProgress = { _, f, m -> reports.add(f to m) })
            try {
                platform.activityManager.launch(ActivitySpec("indexing")) {
                    progress.report(0.5, "halfway")
                    progress.report(1.0, "done")
                }.await()
                assertEquals(listOf(0.5 to "halfway", 1.0 to "done"), reports.toList())
            } finally {
                platform.dispose()
            }
        }
    }

    @Test
    fun cancellationPropagatesThroughCheckCanceled() {
        runBlocking {
            val platform = PlatformCore()
            try {
                val started = CompletableDeferred<Unit>()
                val activity = platform.activityManager.launch(ActivitySpec("long")) {
                    started.complete(Unit)
                    while (true) {
                        checkCanceled()
                        delay(20)
                    }
                }
                started.await()
                activity.cancel()
                assertFailsWith<CancellationException> { activity.await() }
            } finally {
                platform.dispose()
            }
        }
    }

    @Test
    fun writeActionExcludesConcurrentReadAction() {
        runBlocking {
            val platform = PlatformCore()
            try {
                val events = CopyOnWriteArrayList<String>()
                val writeStarted = CompletableDeferred<Unit>()

                val writer = platform.activityManager.launch(ActivitySpec("w")) {
                    writeAction {
                        events.add("w-start")
                        writeStarted.complete(Unit)
                        Thread.sleep(120)
                        events.add("w-end")
                    }
                }
                writeStarted.await()
                val reader = platform.activityManager.launch(ActivitySpec("r")) {
                    readAction { events.add("r") }
                }
                writer.await()
                reader.await()

                assertTrue(
                    events.indexOf("w-end") < events.indexOf("r"),
                    "reader entered before the write action released the model lock: $events",
                )
            } finally {
                platform.dispose()
            }
        }
    }
}
