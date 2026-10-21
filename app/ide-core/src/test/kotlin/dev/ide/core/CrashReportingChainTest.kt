package dev.ide.core

import dev.ide.testkit.withTempDir
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The uncaught-exception handler keeps the app alive after a worker-thread crash (dialog, no chaining) but hands a
 * crash on a fatal thread (Android's main thread) to the previous handler, the system killer. Swallowing a
 * main-thread crash left a process with no looper that Play reported as an ANR.
 */
class CrashReportingChainTest {

    private fun crashOn(name: String) {
        val t = Thread({ throw IllegalStateException("probe on $name") }, name)
        t.start()
        t.join()
    }

    @Test
    fun fatalThreadChainsToThePreviousHandlerAndWorkersAreSwallowed() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val chained = CopyOnWriteArrayList<String>()
        Thread.setDefaultUncaughtExceptionHandler { thread, _ -> chained += thread.name }
        try {
            withTempDir("ide-crash-chain") { dir ->
                IdeServices.bootstrapDemo(dir).use { ide ->
                    val backend = IdeServicesBackend(ide)
                    backend.installCrashReporting(isFatal = { it.name == "fatal-probe" })

                    crashOn("worker-probe")
                    assertEquals(emptyList(), chained, "a worker crash must not reach the system killer")
                    val dialog = assertNotNull(backend.errorEvents.value, "a worker crash surfaces the non-fatal dialog")
                    backend.dismissError(dialog.id)

                    crashOn("fatal-probe")
                    assertEquals(listOf("fatal-probe"), chained, "a fatal-thread crash must chain to the previous handler")
                    assertNull(backend.errorEvents.value, "no dialog for a crash the process will not survive")
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
