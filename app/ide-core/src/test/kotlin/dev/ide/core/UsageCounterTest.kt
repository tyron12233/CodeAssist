package dev.ide.core

import dev.ide.core.perf.UsageCounter
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageCounterTest {

    private val sent = ArrayList<Pair<String, Int>>()
    private val counter = UsageCounter(flushAt = 3) { feature, count -> sent += feature to count }

    @Test
    fun countsAreReportedOncePerFeatureOnFlush() {
        counter.count("preview_layout")
        counter.count("preview_layout")
        counter.count("rename")
        counter.flushAll()
        assertEquals(listOf("preview_layout" to 2, "rename" to 1), sent)
        counter.flushAll()
        assertEquals(2, sent.size, "a flush with nothing counted sends nothing")
    }

    @Test
    fun aCountThatReachesTheCapIsReportedAtOnce() {
        repeat(3) { counter.count("completion_accept") }
        assertEquals(listOf("completion_accept" to 3), sent, "a long session can't hold a count forever")
        counter.count("completion_accept")
        counter.flushAll()
        assertEquals("completion_accept" to 1, sent.last())
    }

    @Test
    fun malformedIdsAreDropped() {
        counter.count("Preview Layout")
        counter.count("/sdcard/project/Main.kt")
        counter.count("")
        counter.flushAll()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun clearForgetsWhatWasNotSent() {
        counter.count("rename")
        counter.clear()
        counter.flushAll()
        assertEquals(emptyList(), sent)
    }
}
