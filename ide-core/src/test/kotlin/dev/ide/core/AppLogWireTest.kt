package dev.ide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips the app-log wire protocol: builds frames exactly as `:applog-runtime`'s `IdeLogBridge` writes
 * them (4-byte big-endian length + UTF-8 payload) and asserts [AppLogWire] reads + parses them back. This is
 * the contract between the injected in-app bridge and the on-device receiver, verified on the plain JVM.
 */
class AppLogWireTest {

    private fun frame(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val len = bytes.size
        out.write((len ushr 24) and 0xFF); out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
        out.write(bytes)
        return out.toByteArray()
    }

    private fun stream(vararg payloads: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        payloads.forEach { out.write(frame(it)) }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun parsesHelloThenRecords() {
        val input = stream(
            "H\t1\tcom.example.app\t1234\t",
            "L\t1700000000000\t1234\t56\tD\tMyTag\thello world",
            "L\t1700000000001\t1234\t56\tE\tAndroidRuntime\tline1\nline2\twith tab",
        )
        val hello = AppLogWire.parse(AppLogWire.readFrame(input)!!)
        assertTrue(hello is AppLogEvent.Hello)
        assertEquals(1, hello.protocolVersion)
        assertEquals("com.example.app", hello.packageName)
        assertEquals(1234, hello.pid)
        assertEquals("", hello.token)

        val r1 = AppLogWire.parse(AppLogWire.readFrame(input)!!)
        assertTrue(r1 is AppLogEvent.Record)
        assertEquals(AppLogLevel.DEBUG, r1.entry.level)
        assertEquals("MyTag", r1.entry.tag)
        assertEquals("hello world", r1.entry.message)
        assertEquals(1700000000000L, r1.entry.timestampMs)
        assertEquals(56, r1.entry.tid)

        // A message may contain tabs and newlines (a stack trace); everything after the 6th tab is preserved.
        val r2 = AppLogWire.parse(AppLogWire.readFrame(input)!!)
        assertTrue(r2 is AppLogEvent.Record)
        assertEquals(AppLogLevel.ERROR, r2.entry.level)
        assertEquals("line1\nline2\twith tab", r2.entry.message)

        // Clean end of stream.
        assertNull(AppLogWire.readFrame(input))
    }

    @Test
    fun rejectsMalformedPayloads() {
        assertNull(AppLogWire.parse(""))
        assertNull(AppLogWire.parse("X\tunknown-kind"))
        assertNull(AppLogWire.parse("L\ttoo\tfew\tfields")) // fewer than 6 tab-separated fields
    }

    @Test
    fun unknownLevelFallsBackToInfo() {
        val r = AppLogWire.parse("L\t0\t1\t1\t?\tTag\tmsg")
        assertTrue(r is AppLogEvent.Record)
        assertEquals(AppLogLevel.INFO, r.entry.level)
    }
}
