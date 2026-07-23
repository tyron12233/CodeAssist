package dev.ide.platform.log

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogTest {

    /** A plugin-scoped logger stamps its source onto every record; a plain logger leaves it null. */
    @Test
    fun `logger source attributes records`() {
        val captured = CopyOnWriteArrayList<LogRecord>()
        val sink = LogSink { captured.add(it) }
        Log.addSink(sink)
        try {
            Log.logger("sync", source = "dev.example.logcat").info("attributed-message")
            Log.logger("core").warn("plain-message")

            val attributed = captured.first { it.message == "attributed-message" }
            assertEquals("dev.example.logcat", attributed.source)
            assertEquals("sync", attributed.tag)

            val plain = captured.first { it.message == "plain-message" }
            assertNull(plain.source)
        } finally {
            Log.removeSink(sink)
        }
    }
}
