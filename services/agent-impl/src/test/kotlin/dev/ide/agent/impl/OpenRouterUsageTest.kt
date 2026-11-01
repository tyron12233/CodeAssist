package dev.ide.agent.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading an OpenRouter key's limits from `GET /api/v1/key` for the usage meters. */
class OpenRouterUsageTest {

    // 2026-11-04 15:30 UTC, a Wednesday.
    private val now = Instant.parse("2026-11-04T15:30:00Z").toEpochMilli()

    @Test
    fun freeRequestsAndNoCapFromTheDocumentedShape() {
        val u = OpenRouterUsage.parse(
            """{"data":{"label":"sk-or-v1-abc","limit":null,"limit_remaining":null,"limit_reset":null,"usage":0,
               "usage_daily":0,"usage_weekly":0,"usage_monthly":0,"is_free_tier":true,"effective_budget":null,
               "free_model_daily_requests":{"limit":50,"remaining":38,"used":12}}}""",
            now,
        )
        assertEquals(12, u.freeRequestsUsed)
        assertEquals(50, u.freeRequestsLimit)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), u.freeResetsAtMs)
        assertNull(u.budgetLimitUsd)
        assertEquals(true, u.isFreeTier)
    }

    @Test
    fun usedIsDerivedFromRemainingWhenMissing() {
        val u = OpenRouterUsage.parse("""{"data":{"free_model_daily_requests":{"limit":1000,"remaining":990}}}""", now)
        assertEquals(10, u.freeRequestsUsed)
    }

    @Test
    fun theBudgetThatBlocksFirstWinsOverTheKeyLimit() {
        val u = OpenRouterUsage.parse(
            """{"data":{"limit":100,"limit_remaining":90,"limit_reset":"monthly","usage_daily":0.4,"usage_monthly":3.5,
               "effective_budget":{"limit_usd":5,"spend_usd":4.25,"remaining_usd":0.75,"reset_interval":"weekly","resets_at":null}}}""",
            now,
        )
        assertEquals(5.0, u.budgetLimitUsd)
        assertEquals(4.25, u.budgetUsedUsd)
        // Next Monday, UTC.
        assertEquals(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli(), u.budgetResetsAtMs)
        assertEquals(0.4, u.spentTodayUsd)
        assertEquals(3.5, u.spentMonthUsd)
    }

    @Test
    fun keyLimitIsUsedWhenNoBudgetIsReported() {
        val u = OpenRouterUsage.parse("""{"data":{"limit":10,"limit_remaining":7.5,"limit_reset":"monthly","usage":2.5}}""", now)
        assertEquals(10.0, u.budgetLimitUsd)
        assertEquals(2.5, u.budgetUsedUsd)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), u.budgetResetsAtMs)
    }

    @Test
    fun anExplicitResetTimeIsTakenAsIs() {
        val u = OpenRouterUsage.parse(
            """{"data":{"effective_budget":{"limit_usd":1,"spend_usd":0.5,"resets_at":"2026-11-04T18:00:00Z"}}}""",
            now,
        )
        assertEquals(Instant.parse("2026-11-04T18:00:00Z").toEpochMilli(), u.budgetResetsAtMs)
    }

    @Test
    fun aCapThatNeverResetsHasNoResetTime() {
        val u = OpenRouterUsage.parse("""{"data":{"limit":10,"limit_remaining":10,"limit_reset":null}}""", now)
        assertNull(u.budgetResetsAtMs)
    }
}
