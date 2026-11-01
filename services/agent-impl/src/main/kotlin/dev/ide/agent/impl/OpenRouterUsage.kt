package dev.ide.agent.impl

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * An OpenRouter key's usage against its limits, from `GET /api/v1/key`.
 *
 * - [freeRequestsUsed] / [freeRequestsLimit]: requests to `:free` models today (50 a day, or 1000 once the account
 *   has bought credits), resetting at UTC midnight ([freeResetsAtMs]). Every agent step is one request.
 * - [budgetUsedUsd] / [budgetLimitUsd]: the spending cap that would block requests first (a guardrail or workspace
 *   budget, else the key's own limit), null when nothing caps it.
 */
data class OpenRouterUsage(
    val freeRequestsUsed: Int? = null,
    val freeRequestsLimit: Int? = null,
    val freeResetsAtMs: Long? = null,
    val budgetUsedUsd: Double? = null,
    val budgetLimitUsd: Double? = null,
    val budgetResetsAtMs: Long? = null,
    val spentTodayUsd: Double? = null,
    val spentMonthUsd: Double? = null,
    val isFreeTier: Boolean? = null,
) {
    companion object {
        private const val KEY_URL = "https://openrouter.ai/api/v1/key"

        suspend fun fetch(transport: LlmTransport, key: String, nowMs: Long = System.currentTimeMillis()): OpenRouterUsage =
            parse(transport.get(KEY_URL, mapOf("Authorization" to "Bearer $key")), nowMs)

        fun parse(json: String, nowMs: Long): OpenRouterUsage {
            val data = AgentJson.parseToJsonElement(json).asObj()?.get("data").asObj()
                ?: throw IllegalArgumentException("OpenRouter sent no key details.")
            val free = data["free_model_daily_requests"].asObj()
            val freeLimit = free?.get("limit").asStr()?.toIntOrNull()
            val freeUsed = free?.get("used").asStr()?.toIntOrNull()
                ?: free?.get("remaining").asStr()?.toIntOrNull()?.let { r -> freeLimit?.let { it - r } }

            // The budget that blocks first; the key's own `limit` only when no budget object is reported.
            val effective = data["effective_budget"].asObj()
            val budgetLimit: Double?
            val budgetUsed: Double?
            val budgetResets: Long?
            if (effective != null && effective["limit_usd"].asStr()?.toDoubleOrNull() != null) {
                budgetLimit = effective["limit_usd"].asStr()?.toDoubleOrNull()
                budgetUsed = effective["spend_usd"].asStr()?.toDoubleOrNull()
                    ?: effective["remaining_usd"].asStr()?.toDoubleOrNull()?.let { budgetLimit!! - it }
                budgetResets = effective["resets_at"].asStr()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: nextReset(effective["reset_interval"].asStr(), nowMs)
            } else {
                budgetLimit = data["limit"].asStr()?.toDoubleOrNull()
                budgetUsed = budgetLimit?.let { lim ->
                    data["limit_remaining"].asStr()?.toDoubleOrNull()?.let { lim - it } ?: data["usage"].asStr()?.toDoubleOrNull()
                }
                budgetResets = budgetLimit?.let { nextReset(data["limit_reset"].asStr(), nowMs) }
            }

            return OpenRouterUsage(
                freeRequestsUsed = freeUsed,
                freeRequestsLimit = freeLimit,
                freeResetsAtMs = freeLimit?.let { nextReset("daily", nowMs) },
                budgetUsedUsd = budgetUsed?.coerceAtLeast(0.0),
                budgetLimitUsd = budgetLimit,
                budgetResetsAtMs = budgetResets,
                spentTodayUsd = data["usage_daily"].asStr()?.toDoubleOrNull(),
                spentMonthUsd = data["usage_monthly"].asStr()?.toDoubleOrNull(),
                isFreeTier = data["is_free_tier"].asStr()?.toBooleanStrictOrNull(),
            )
        }

        /** The next UTC boundary for a `daily` / `weekly` (Monday) / `monthly` window; null for none or unknown. */
        fun nextReset(interval: String?, nowMs: Long): Long? {
            val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
            val next: LocalDate = when (interval?.lowercase()) {
                "daily", "day" -> today.plusDays(1)
                "weekly", "week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                "monthly", "month" -> today.with(TemporalAdjusters.firstDayOfNextMonth())
                else -> return null
            }
            return next.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
    }
}
