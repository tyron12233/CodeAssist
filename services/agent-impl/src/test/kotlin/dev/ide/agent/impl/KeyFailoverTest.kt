package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyFailoverTest {
    private val request = LlmRequest("m", null, listOf(LlmMessage.user("hi")))

    private fun refused(kind: LlmErrorKind, retryAfterMs: Long? = null) = LlmStreamEvent.Failed(
        "refused",
        LlmHttpException("Refused.\nprovider detail", retryAfterMs = retryAfterMs, retryable = kind.retryable, kind = kind),
    )

    private val answer = listOf(LlmStreamEvent.TextDelta("ok"), LlmStreamEvent.Completed(StopReason.END_TURN))

    /** A client per key: each key answers with the events listed for it, and every call is recorded. */
    private class Keys(val replies: Map<String, List<LlmStreamEvent>>) {
        val calls = mutableListOf<String>()
        fun client(key: String) = LlmClient { calls += key; flowOf(*replies.getValue(key).toTypedArray()) }
    }

    private fun run(client: LlmClient) = runBlocking { client.chat(request).toList() }

    @Test
    fun aRateLimitedKeyHandsTheRequestToTheNextOne() {
        val keys = Keys(mapOf("a" to listOf(refused(LlmErrorKind.RATE_LIMIT, 30_000)), "b" to answer))
        val client = KeyFailoverClient(ApiKeyPool(listOf("a", "b")), keys::client)

        assertEquals(answer, run(client))
        assertEquals(listOf("a", "b"), keys.calls)
        // The benched key is skipped on the next request.
        run(client)
        assertEquals(listOf("a", "b", "b"), keys.calls)
    }

    @Test
    fun theFirstKeyIsUsedAgainOnceItsWaitIsOver() {
        var now = 0L
        val keys = Keys(mapOf("a" to listOf(refused(LlmErrorKind.RATE_LIMIT, 10_000)), "b" to answer))
        val client = KeyFailoverClient(ApiKeyPool(listOf("a", "b")) { now }, keys::client)
        run(client)
        now = 10_000
        run(client)
        assertEquals(listOf("a", "b", "a", "b"), keys.calls)
    }

    @Test
    fun anErrorEveryKeyWouldHitIsNotRetriedOnTheOthers() {
        val bad = refused(LlmErrorKind.CONTEXT_LENGTH)
        val keys = Keys(mapOf("a" to listOf(bad), "b" to answer))
        assertEquals(listOf<LlmStreamEvent>(bad), run(KeyFailoverClient(ApiKeyPool(listOf("a", "b")), keys::client)))
        assertEquals(listOf("a"), keys.calls)
    }

    @Test
    fun aFailureAfterOutputIsNeverResent() {
        val midStream = listOf(LlmStreamEvent.TextDelta("par"), refused(LlmErrorKind.RATE_LIMIT))
        val keys = Keys(mapOf("a" to midStream, "b" to answer))
        assertEquals(midStream, run(KeyFailoverClient(ApiKeyPool(listOf("a", "b")), keys::client)))
        assertEquals(listOf("a"), keys.calls)
    }

    @Test
    fun shortRateLimitsOnEveryKeyComeBackAsOneWaitableRateLimit() {
        val keys = Keys(mapOf(
            "a" to listOf(refused(LlmErrorKind.RATE_LIMIT, 40_000)),
            "b" to listOf(refused(LlmErrorKind.RATE_LIMIT, 20_000)),
        ))
        val events = run(KeyFailoverClient(ApiKeyPool(listOf("a", "b")) { 0L }, keys::client))
        val cause = (events.single() as LlmStreamEvent.Failed).cause as LlmHttpException
        assertEquals(LlmErrorKind.RATE_LIMIT, cause.kind)
        assertEquals(20_000, cause.retryAfterMs)
        assertTrue(cause.message!!.startsWith("All 2 API keys are rate limited."), cause.message)
    }

    @Test
    fun spentKeysAreReportedTogether() {
        val keys = Keys(mapOf(
            "a" to listOf(refused(LlmErrorKind.DAILY_LIMIT)),
            "b" to listOf(refused(LlmErrorKind.AUTH)),
            "c" to listOf(refused(LlmErrorKind.DAILY_LIMIT)),
        ))
        val events = run(KeyFailoverClient(ApiKeyPool(listOf("a", "b", "c")) { 0L }, keys::client))
        val failed = events.single() as LlmStreamEvent.Failed
        assertTrue(failed.message.startsWith("All 3 API keys for this provider are unavailable (2 at the daily limit, 1 rejected)."), failed.message)
        assertTrue(failed.message.endsWith("\nprovider detail"), failed.message)
        assertEquals(LlmErrorKind.DAILY_LIMIT, (failed.cause as LlmHttpException).kind)
    }

    @Test
    fun oneKeyPassesTheProviderErrorThroughUnchanged() {
        val bad = refused(LlmErrorKind.RATE_LIMIT, 5_000)
        val keys = Keys(mapOf("a" to listOf(bad)))
        assertEquals(listOf<LlmStreamEvent>(bad), run(KeyFailoverClient(ApiKeyPool(listOf("a", " a ", "")), keys::client)))
    }
}
