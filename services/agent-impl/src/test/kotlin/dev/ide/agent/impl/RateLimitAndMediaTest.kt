package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.StopReason
import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitAndMediaTest {
    private fun geminiQuotaBody(quotaId: String, value: String?, model: String = "gemini-2.5-pro"): String {
        val quotaValue = value?.let { ""","quotaValue":"$it"""" }.orEmpty()
        return """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
             "message":"You exceeded your current quota, please check your plan and billing details. * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: ${value ?: "10"}, model: $model\nPlease retry in 39s.",
             "details":[
               {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                 {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                  "quotaId":"$quotaId","quotaDimensions":{"location":"global","model":"$model"}$quotaValue}]},
               {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"39s"}]}}
        """.trimIndent()
    }

    @Test
    fun aModelWithNoFreeQuotaIsNotMistakenForARateLimit() {
        val parsed = LlmErrors.parseHttp(429, geminiQuotaBody("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "0"), null)
        assertEquals(LlmErrorKind.MODEL_NOT_ON_PLAN, parsed.kind)
        assertFalse(parsed.retryable)
        assertTrue(parsed.message.contains("'gemini-2.5-pro' has no quota"), parsed.message)
    }

    @Test
    fun aSpentDailyAllowanceIsReportedAsDaily() {
        val parsed = LlmErrors.parseHttp(429, geminiQuotaBody("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "gemini-3.8-flash"), null)
        assertEquals(LlmErrorKind.DAILY_LIMIT, parsed.kind)
        assertFalse(parsed.retryable)
        assertTrue(parsed.message.contains("20 per day"), parsed.message)
    }

    @Test
    fun aPerMinuteLimitStaysRetryableAndCarriesItsCeiling() {
        val parsed = LlmErrors.parseHttp(429, geminiQuotaBody("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "10", "gemini-3.8-flash"), null)
        assertEquals(LlmErrorKind.RATE_LIMIT, parsed.kind)
        assertTrue(parsed.retryable)
        assertEquals(39_000L, parsed.retryAfterMs)
        assertEquals(10L, parsed.quota?.limit)
        assertEquals(QuotaInfo.Window.MINUTE, parsed.quota?.window)
    }

    @Test
    fun newestFlashPrefersTheLatestStableGeneralModel() {
        val ids = listOf(
            "models/gemini-2.5-flash", "models/gemini-2.5-pro", "models/gemini-3.5-flash-lite",
            "models/gemini-3.8-flash-preview", "models/gemini-3.8-flash", "models/gemini-3.8-flash-image",
            "models/gemini-3.7-flash", "models/gemini-3.1-pro-preview",
        )
        assertEquals("gemini-3.8-flash", GeminiModels.newestFlash(ids))
        assertEquals("gemini-3.9-flash-preview", GeminiModels.newestFlash(ids + "gemini-3.9-flash-preview"))
        assertNull(GeminiModels.newestFlash(listOf("gemini-2.5-pro")))
        assertEquals(
            "gemini-3.8-flash",
            GeminiProvider(CapturingTransport()).preferredModel(ids.map { LlmModelInfo(it.removePrefix("models/"), it) }),
        )
    }

    private fun geminiBody(request: LlmRequest): String {
        val transport = CapturingTransport()
        runBlocking { GeminiProvider(transport).client(ProviderConfig("k")).chat(request).toList() }
        return transport.lastBody!!
    }

    private val tool = ToolSpec("read_file", "Read a file", """{"type":"object","properties":{}}""")

    @Test
    fun gemini3TakesAThinkingLevelAndNeverABudget() {
        val body = geminiBody(
            LlmRequest("gemini-3.8-flash", "sys", listOf(LlmMessage.user("hi")), maxTokens = 8192, effort = "medium", thinkingBudget = 4096),
        )
        assertTrue(body.contains("\"thinkingLevel\":\"medium\""), body)
        assertFalse(body.contains("thinkingBudget"), body)
        // The reasoning allowance rides on top of the answer cap, because the cap counts thinking too.
        assertTrue(body.contains("\"maxOutputTokens\":${8192 + 12_288}"), body)
    }

    @Test
    fun gemini25BudgetIsAddedToTheOutputCap() {
        val body = geminiBody(LlmRequest("gemini-2.5-flash", "sys", listOf(LlmMessage.user("hi")), maxTokens = 8192, thinkingBudget = 16_384))
        assertTrue(body.contains("\"thinkingBudget\":16384"), body)
        assertTrue(body.contains("\"maxOutputTokens\":24576"), body)
    }

    @Test
    fun searchGroundingIsDroppedWhereItCannotSitBesideFunctionCalls() {
        val old = geminiBody(LlmRequest("gemini-2.5-flash", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool), webSearch = true))
        assertFalse(old.contains("google_search"), old)
        assertTrue(old.contains("function_declarations"), old)
        val new = geminiBody(LlmRequest("gemini-3.8-flash", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool), webSearch = true))
        assertTrue(new.contains("google_search"), new)
        assertTrue(new.contains("function_declarations"), new)
    }

    @Test
    fun pacerWaitsForTheOldestRequestToAgeOutOnceTheCeilingIsReached() {
        var now = 0L
        val pacer = RequestPacer(configuredRpm = 2, clock = { now })
        assertEquals(0, pacer.delayFor(10))
        pacer.record(10)
        now = 10_000
        assertEquals(0, pacer.delayFor(10))
        pacer.record(10)
        now = 20_000
        assertEquals(40_000, pacer.delayFor(10))
        now = 60_000
        assertEquals(0, pacer.delayFor(10))
    }

    @Test
    fun pacerLearnsAnObservedCeilingWhenTheErrorStatesNone() {
        var now = 0L
        val pacer = RequestPacer(clock = { now })
        repeat(5) { pacer.record(100); now += 1_000 }
        assertNull(pacer.rpm)
        pacer.learn(QuotaInfo(QuotaInfo.Window.MINUTE, QuotaInfo.Metric.REQUESTS, limit = null, model = null))
        assertEquals(4, pacer.rpm)
        // A daily quota says nothing about the per-minute rate.
        pacer.learn(QuotaInfo(QuotaInfo.Window.DAY, QuotaInfo.Metric.REQUESTS, limit = 3, model = null))
        assertEquals(4, pacer.rpm)
    }

    @Test
    fun pacerHoldsBackARequestThatWouldBreakTheTokenCeiling() {
        var now = 0L
        val pacer = RequestPacer(configuredTpm = 1_000, clock = { now })
        pacer.record(700)
        now = 5_000
        assertEquals(55_000, pacer.delayFor(400))
        assertEquals(0, pacer.delayFor(300))
    }

    @Test
    fun loopWaitsOutAPerMinuteLimitThenFinishes() {
        val limited = LlmHttpException(
            "Rate limit reached", 429, retryAfterMs = 1_000, retryable = true, kind = LlmErrorKind.RATE_LIMIT,
            quota = QuotaInfo(QuotaInfo.Window.MINUTE, QuotaInfo.Metric.REQUESTS, limit = 5, model = "m"),
        )
        var calls = 0
        val client = LlmClient {
            calls++
            if (calls == 1) flowOf(LlmStreamEvent.Failed(limited.message!!, limited))
            else flowOf(LlmStreamEvent.TextDelta("done"), LlmStreamEvent.Completed(StopReason.END_TURN))
        }
        val pacer = RequestPacer()
        val loop = AgentLoop(client, "m", SimpleToolRegistry(emptyList()), AllowAllGate, { "sys" }, pacer = pacer)
        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send("hi", AgentEventSink { events += it }) }
        assertEquals(2, calls)
        assertTrue(events.any { it is AgentEvent.Waiting })
        assertTrue(events.none { it is AgentEvent.Error })
        assertTrue(events.last() is AgentEvent.TurnCompleted)
        assertEquals(5, pacer.rpm)
    }

    @Test
    fun loopDoesNotWaitOnADailyLimit() {
        val daily = LlmHttpException("Daily limit reached", 429, retryable = false, kind = LlmErrorKind.DAILY_LIMIT)
        var calls = 0
        val client = LlmClient { calls++; flowOf(LlmStreamEvent.Failed(daily.message!!, daily)) }
        val loop = AgentLoop(client, "m", SimpleToolRegistry(emptyList()), AllowAllGate, { "sys" })
        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send("hi", AgentEventSink { events += it }) }
        assertEquals(1, calls)
        assertTrue(events.none { it is AgentEvent.Waiting })
        assertTrue(events.last() is AgentEvent.Error)
    }

    private val png = ContentPart.Image("image/png", "iVBORw0KGgo=")

    @Test
    fun everyProviderSendsAttachedImages() {
        val request = LlmRequest("model", "sys", listOf(LlmMessage.user("what is this?", listOf(png))))

        val anthropic = CapturingTransport(emptyList())
        runBlocking { AnthropicProvider(anthropic).client(ProviderConfig("k")).chat(request).toList() }
        assertTrue(anthropic.lastBody!!.contains("""{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}"""), anthropic.lastBody)

        val openai = CapturingTransport(emptyList())
        runBlocking { OpenAiProvider(openai).client(ProviderConfig("k")).chat(request).toList() }
        assertTrue(openai.lastBody!!.contains("data:image/png;base64,iVBORw0KGgo="), openai.lastBody)

        val gemini = geminiBody(request.copy(model = "gemini-3.8-flash"))
        assertTrue(gemini.contains("""{"inline_data":{"mime_type":"image/png","data":"iVBORw0KGgo="}}"""), gemini)
    }

    @Test
    fun toolImagesReachEachProvider() {
        val messages = listOf(
            LlmMessage.user("look"),
            LlmMessage.assistant(listOf(ContentPart.ToolUse("c1", "view_image", "{}"))),
            LlmMessage.toolResult("c1", "a screenshot", images = listOf(png)),
        )
        val request = LlmRequest("model", "sys", messages)

        val anthropic = CapturingTransport(emptyList())
        runBlocking { AnthropicProvider(anthropic).client(ProviderConfig("k")).chat(request).toList() }
        assertTrue(anthropic.lastBody!!.contains(""""tool_use_id":"c1","content":[{"type":"text","text":"a screenshot"},{"type":"image""""), anthropic.lastBody)

        // Both OpenAI dialects carry tool results as text, so the images follow them in a user turn.
        val openai = CapturingTransport(emptyList())
        runBlocking { OpenAiProvider(openai).client(ProviderConfig("k")).chat(request).toList() }
        val body = openai.lastBody!!
        val result = body.indexOf("\"type\":\"function_call_output\"")
        assertTrue(result >= 0 && result < body.indexOf("Images returned by the tool calls above"), body)

        val gateway = CapturingTransport(emptyList())
        runBlocking { OpenAiProvider(gateway).client(ProviderConfig("k", baseUrl = "https://gateway.example")).chat(request).toList() }
        val chatBody = gateway.lastBody!!
        val toolMessage = chatBody.indexOf("\"role\":\"tool\"")
        assertTrue(toolMessage >= 0 && toolMessage < chatBody.indexOf("Images returned by the tool calls above"), chatBody)

        val gemini = geminiBody(request.copy(model = "gemini-3.8-flash"))
        assertTrue(gemini.indexOf("functionResponse") < gemini.indexOf("inline_data"), gemini)
    }

    @Test
    fun compactorDropsImagesFromStaleToolResults() {
        val compactor = HistoryCompactor(maxToolResultChars = 10, keepRecentToolMessages = 1, triggerChars = 1_000, targetChars = 100)
        val history = listOf(
            LlmMessage.user("go"),
            LlmMessage.toolResult("old", "small", images = listOf(png)),
            LlmMessage.toolResult("new", "recent", images = listOf(png)),
        )
        val out = compactor.compact(history)
        val old = out[1].content.single() as ContentPart.ToolResultPart
        val new = out[2].content.single() as ContentPart.ToolResultPart
        assertTrue(old.images.isEmpty())
        assertTrue(old.content.contains("image(s) elided"), old.content)
        assertEquals(1, new.images.size)
    }
}
