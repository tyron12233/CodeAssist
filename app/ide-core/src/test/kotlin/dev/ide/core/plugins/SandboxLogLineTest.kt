package dev.ide.core.plugins

import dev.ide.core.PluginSandbox
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogLevel
import dev.ide.platform.log.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxLogLineTest {

    @Test
    fun escapeRoundTripsAwkwardText() {
        for (s in listOf("", "plain", "a\tb", "two\nlines", "back\\slash", "\\n is not a newline", "cr\r\n", "\\\t\\n\n")) {
            val escaped = SandboxLogLine.escape(s)
            assertTrue('\n' !in escaped && '\t' !in escaped, "escaped form of '$s' must be one field: '$escaped'")
            assertEquals(s, SandboxLogLine.unescape(escaped))
        }
    }

    @Test
    fun aRecordIsExactlyOneLine() {
        val line = SandboxLogLine.encode(
            LogRecord(LogLevel.WARN, "t\tag", "multi\nline", IllegalStateException("boom"), 0L, "main", "dev.example"),
        )
        assertEquals(1, line.count { it == '\n' })
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun replayLogsUnderTheSandboxSourceWithTheOriginalStack() {
        val marker = "replay-${System.nanoTime()}"
        val cause = IllegalArgumentException("probe cause")
        val line = SandboxLogLine.encode(
            LogRecord(LogLevel.ERROR, "probe", "$marker\nsecond line", cause, 0L, "main", "dev.example.plugin"),
        )
        SandboxLogLine.replay(line.trimEnd('\n'))

        val replayed = Log.recent().last { it.message.startsWith(marker) }
        assertEquals(LogLevel.ERROR, replayed.level)
        assertEquals("probe", replayed.tag)
        assertEquals("$marker\nsecond line", replayed.message)
        assertEquals(PluginSandbox.LOG_SOURCE_PREFIX + "dev.example.plugin", replayed.source)
        assertTrue(PluginSandbox.isRelayed(replayed))
        val stack = java.io.StringWriter().also { replayed.throwable!!.printStackTrace(java.io.PrintWriter(it)) }.toString()
        assertEquals(cause.stackTraceToString(), stack)
    }

    @Test
    fun aRecordWithoutACauseReplaysWithout() {
        val marker = "nocause-${System.nanoTime()}"
        SandboxLogLine.replay(SandboxLogLine.encodeNote("dev.example.plugin", marker).trimEnd('\n'))
        val replayed = Log.recent().last { it.message == marker }
        assertEquals(LogLevel.WARN, replayed.level)
        assertNull(replayed.throwable)
    }

    @Test
    fun malformedLinesAreDropped() {
        SandboxLogLine.replay("not\ta\trecord")
        SandboxLogLine.replay("")
        assertTrue(Log.recent().none { it.message == "a" && it.tag == "not" })
    }

    @Test
    fun ownLogsAreNotRelayed() {
        assertTrue(!PluginSandbox.isRelayed(LogRecord(LogLevel.ERROR, "x", "y", null, 0L, "main", "dev.example.plugin")))
        assertTrue(!PluginSandbox.isRelayed(LogRecord(LogLevel.ERROR, "x", "y", null, 0L, "main", null)))
    }
}
