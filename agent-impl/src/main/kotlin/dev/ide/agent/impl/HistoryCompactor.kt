package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole

/**
 * Trims the conversation the agent loop re-sends to the model each iteration. An agentic turn re-sends the
 * whole growing history on every step, and tool results (file reads, searches, diagnostics) are by far the
 * largest and most repeated payload, so re-billing them verbatim on each step dominates input-token cost on
 * token-metered tiers.
 *
 * This elides the *body* of a stale, oversized tool result (older than the [keepRecentToolMessages] most
 * recent ones) down to a head excerpt plus a marker telling the model it can re-run the tool for the full
 * output. The most recent tool results — the model's active working set — are kept verbatim, and user and
 * assistant text is never touched, so the conversation's meaning is preserved while the repeated bulk drops
 * out. Compaction produces a fresh message list per call; the loop's stored history is left intact so a
 * retry re-derives the same view.
 */
class HistoryCompactor(
    /** A stale tool result longer than this many characters is truncated; shorter ones are left alone. */
    private val maxToolResultChars: Int = 4_000,
    /** How many of the most-recent tool-result messages to keep verbatim. */
    private val keepRecentToolMessages: Int = 4,
) {
    fun compact(history: List<LlmMessage>): List<LlmMessage> {
        val toolIndices = history.indices.filter { history[it].role == LlmRole.TOOL }
        if (toolIndices.size <= keepRecentToolMessages) return history.toList()
        val firstKept = toolIndices[toolIndices.size - keepRecentToolMessages]
        return history.mapIndexed { i, m ->
            if (m.role == LlmRole.TOOL && i < firstKept) elide(m) else m
        }
    }

    private fun elide(message: LlmMessage): LlmMessage {
        val content = message.content.map { part ->
            if (part is ContentPart.ToolResultPart && part.content.length > maxToolResultChars) {
                part.copy(content = truncate(part.content))
            } else {
                part
            }
        }
        return message.copy(content = content)
    }

    private fun truncate(text: String): String {
        val head = text.take(maxToolResultChars)
        val elided = text.length - head.length
        return "$head\n… [$elided characters elided to save context; call the tool again if you need the full result.]"
    }
}
