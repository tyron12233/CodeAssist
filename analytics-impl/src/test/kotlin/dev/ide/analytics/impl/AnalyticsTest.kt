package dev.ide.analytics.impl

import dev.ide.analytics.AnalyticsEvent
import dev.ide.analytics.AnalyticsSink
import dev.ide.analytics.DeviceInfo
import dev.ide.analytics.EventBatch
import dev.ide.analytics.EventCategory
import dev.ide.analytics.Events
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsTest {

    private val device = DeviceInfo("3.0.2", 33, 34, "Pixel 8", "Google", "arm64-v8a", "en-US")

    /** A sink that records what it received and can be told to "fail" (simulate offline). */
    private class FakeSink(var accept: Boolean = true) : AnalyticsSink {
        val received = CopyOnWriteArrayList<AnalyticsEvent>()
        override fun send(batch: EventBatch): Boolean {
            if (!accept) return false
            received.addAll(batch.events)
            return true
        }
    }

    private fun ev(name: String) = AnalyticsEvent(name, EventCategory.USAGE, mapOf("k" to "v"))

    @Test
    fun queueCodecRoundTripsTrickyValues() {
        val e = AnalyticsEvent(
            "build_result",
            EventCategory.PERFORMANCE,
            mapOf("msg" to "a\tb\nc%d", "key=with=eq" to "x"),
        )
        val decoded = EventQueueCodec.decode(EventQueueCodec.encode(e))
        assertEquals(e, decoded)
    }

    @Test
    fun garbledQueueLineDecodesToNull() {
        assertEquals(null, EventQueueCodec.decode(""))
        assertEquals(null, EventQueueCodec.decode("onlyonefield"))
    }

    @Test
    fun disabledServiceCollectsNothing() {
        val sink = FakeSink()
        val svc = DefaultAnalyticsService("i", "s", device, sink, initialConsent = false, flushIntervalMs = 50, batchSize = 1)
        svc.track(ev("project_open"))
        svc.flush()
        assertTrue(sink.received.isEmpty(), "no events should ship while consent is denied")
        svc.close()
    }

    @Test
    fun enabledServiceShipsBatches() {
        val sink = FakeSink()
        val svc = DefaultAnalyticsService("i", "s", device, sink, initialConsent = true, batchSize = 100)
        svc.track(ev("a"))
        svc.track(ev("b"))
        svc.flush()
        assertEquals(listOf("a", "b"), sink.received.map { it.name })
        svc.close()
    }

    @Test
    fun revokingConsentDropsBufferedEvents() {
        val sink = FakeSink(accept = false) // can't ship → events stay buffered
        val svc = DefaultAnalyticsService("i", "s", device, sink, initialConsent = true, batchSize = 100)
        svc.track(ev("a"))
        svc.flush() // fails → retained
        svc.enabled = false // revoke
        sink.accept = true
        svc.enabled = true
        svc.flush()
        assertTrue(sink.received.isEmpty(), "revoking consent must discard buffered events")
        svc.close()
    }

    @Test
    fun failedSendKeepsEventsForRetry() {
        val sink = FakeSink(accept = false)
        val svc = DefaultAnalyticsService("i", "s", device, sink, initialConsent = true, batchSize = 100)
        svc.track(ev("a"))
        svc.flush() // fails, retained
        sink.accept = true
        svc.flush() // succeeds now
        assertEquals(listOf("a"), sink.received.map { it.name })
        svc.close()
    }

    @Test
    fun crashReportIsScrubbed() {
        val boom = IllegalStateException("secret path /Users/bob/project/Main.java leaked here")
        val props = dev.ide.analytics.CrashScrub.scrub(boom)
        assertEquals("java.lang.IllegalStateException", props["exception"])
        val frames = props["frames"] ?: ""
        assertFalse(frames.contains("/Users/"), "no file paths in frames")
        assertFalse((props.values.joinToString(" ")).contains("secret path"), "no exception message anywhere")
        assertTrue(frames.lines().all { it.startsWith("dev.ide.") || it.startsWith("… (") }, "only dev.ide.* frames: $frames")
    }

    @Test
    fun crashWithoutOwnTopFrameStillPinsACauseFrame() {
        // A deep framework crash: the thrown exception's own trace is all foreign (Compose/Android), but our
        // code is on the cause chain. The scrubber must still surface that own frame (was empty before).
        val cause = RuntimeException("root").apply {
            stackTrace = arrayOf(StackTraceElement("dev.ide.core.Foo", "bar", "Foo.kt", 42))
        }
        val top = RuntimeException("wrap", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement("androidx.compose.runtime.Recomposer", "run", "Recomposer.kt", 1),
                StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            )
        }
        assertEquals("dev.ide.core.Foo.bar:42", dev.ide.analytics.CrashScrub.ownFrames(top))
    }

    @Test
    fun analyticsLogSinkForwardsErrorsScrubbedAndThrottled() {
        val sink = FakeSink()
        val svc = DefaultAnalyticsService("i", "s", device, sink, initialConsent = true, batchSize = 100)
        val logSink = AnalyticsLogSink(svc, throttleMs = 60_000)
        val boom = IllegalStateException("/Users/bob/secret.kt blew up")
        fun rec(level: dev.ide.platform.log.LogLevel) =
            dev.ide.platform.log.LogRecord(level, "engine", "boom", boom, 0L, "t")

        logSink.log(rec(dev.ide.platform.log.LogLevel.WARN))   // not ERROR → ignored
        logSink.log(rec(dev.ide.platform.log.LogLevel.ERROR))  // sent
        logSink.log(rec(dev.ide.platform.log.LogLevel.ERROR))  // same type within window → throttled
        svc.flush()

        assertEquals(1, sink.received.size)
        val e = sink.received.single()
        assertEquals(Events.ERROR_LOGGED, e.name)
        assertEquals(EventCategory.CRASH, e.category)
        assertFalse(e.props.values.joinToString(" ").contains("/Users/"), "no paths forwarded")
        svc.close()
    }
}
