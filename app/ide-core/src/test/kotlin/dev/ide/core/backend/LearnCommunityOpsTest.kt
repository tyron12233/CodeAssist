package dev.ide.core.backend

import dev.ide.store.LearnAuthor
import dev.ide.store.LearnCommunityService
import dev.ide.store.LearnThreadSummary
import dev.ide.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearnCommunityOpsTest {

    private class Fake : LearnCommunityService {
        var posted: List<Any?>? = null
        override fun communityAvailable() = true
        override fun threads(lessonId: String, stepId: String?, limit: Int, offset: Int): StoreResult<List<LearnThreadSummary>> =
            StoreResult.Ok(
                listOf(
                    LearnThreadSummary(
                        "t1", lessonId, "kt-cap-i", "Stuck on the loop", LearnAuthor("ana", "Ana"), 2, true, true,
                        "2026-11-04T01:02:03.123+00:00", "2026-11-04T02:00:00+00:00", false,
                    ),
                ),
            )
        override fun createThread(
            lessonId: String, stepId: String?, title: String, body: String, code: String?, language: String?, errorText: String?,
        ): StoreResult<String> {
            posted = listOf(title, body, code, language, errorText)
            return StoreResult.Failed("Slow down: 10 questions a day", 400)
        }
        override fun vote(postId: String, up: Boolean): StoreResult<Int> = StoreResult.Unavailable("offline")
    }

    private val fake = Fake()
    private val ops = LearnCommunityOps(
        fake,
        lessonTitle = { if (it == "kt-basics-capstone") "Score report" else null },
        stepTitle = { _, step -> if (step == "kt-cap-i") "Score report exercise" else null },
    )

    @Test
    fun threadsGainStepTitlesAndMillis() = runBlocking {
        val t = ops.threads("kt-basics-capstone", null)!!.single()
        assertEquals("Score report exercise", t.stepTitle)
        assertEquals(1793754123123L, t.createdAtMs)
        assertEquals("Ana", t.author.name)
        assertEquals("ana", t.author.handle)
    }

    @Test
    fun writesTrimAndDropBlankAttachmentsAndReturnTheServersSentence() = runBlocking {
        val r = ops.ask("kt-basics-capstone", "kt-cap-i", "  Why does it fail?  ", " details ", "   ", "kotlin", "")
        assertEquals("Slow down: 10 questions a day", r.error)
        assertEquals(listOf("Why does it fail?", "details", null, "kotlin", null), fake.posted)
        assertEquals("offline", ops.vote("p1", true))
    }

    @Test
    fun anUnparseableTimestampIsZeroNotACrash() = runBlocking {
        assertNull(ops.thread("missing"))
    }
}
