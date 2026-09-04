package dev.ide.ui.components

import dev.ide.ui.backend.AppLogLineUi
import dev.ide.ui.backend.UiLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Logcat tab's copy-all button puts on the clipboard. */
class LogcatCopyTest {

    @Test
    fun eachRecordBecomesOneLineWithItsMetadata() {
        val text = renderLogcatForCopy(
            listOf(
                AppLogLineUi("started", UiLogLevel.Info, tag = "MainActivity", pid = 4321, tid = 4321, timeLabel = "12:00:01.100"),
                AppLogLineUi("slow frame", UiLogLevel.Warn, tag = "Choreographer", pid = 4321, tid = 4399, timeLabel = "12:00:02.250"),
            )
        )
        assertEquals(
            "12:00:01.100  4321-4321  I/MainActivity: started\n" +
                "12:00:02.250  4321-4399  W/Choreographer: slow frame",
            text,
        )
    }

    @Test
    fun missingMetadataIsLeftOut() {
        val text = renderLogcatForCopy(listOf(AppLogLineUi("bare", UiLogLevel.Debug)))
        assertEquals("D: bare", text)
    }

    /** A forwarded crash arrives as ONE record whose message holds the whole stack trace — keep every line. */
    @Test
    fun multiLineCrashKeepsItsOwnLineBreaks() {
        val trace = "FATAL EXCEPTION: main\njava.lang.IllegalStateException\n\tat com.example.A.b(A.kt:7)"
        val text = renderLogcatForCopy(listOf(AppLogLineUi(trace, UiLogLevel.Error, tag = "AndroidRuntime", pid = 7, tid = 7)))
        assertEquals("7-7  E/AndroidRuntime: $trace", text)
    }
}
