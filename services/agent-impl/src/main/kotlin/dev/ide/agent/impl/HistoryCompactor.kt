package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole

/**
 * Trims the conversation the agent loop re-sends to the model each iteration. An agentic turn re-sends the
 * whole growing history on every step, and tool results (file reads, searches, diagnostics) are by far the
 * largest and most repeated payload.
 *
 * Trimming fights prompt caching, and caching usually wins. A cached prefix is re-billed at a fraction of the
 * input rate, so re-sending a stale tool result verbatim is nearly free — whereas *rewriting* it changes the
 * prompt bytes at a position the whole rest of the conversation sits behind, and every turn after it falls out
 * of cache at full price. Eliding a little more on every step would therefore invalidate the cache on every
 * step, spending far more than it saves.
 *
 * So compaction runs on hysteresis instead of continuously, and its decisions are permanent:
 * - Nothing is elided until the transcript crosses [triggerChars]; below that the re-sent prefix is
 *   byte-identical from one step to the next and the provider serves it from cache.
 * - When it does trigger, it elides the oldest stale tool results in one pass until the transcript is back
 *   under [targetChars], so the next trigger is many steps away rather than one.
 * - An elided result stays elided ([elidedCallIds]) even once it is no longer the oldest, so the prefix only
 *   ever changes at a trigger and never drifts back.
 *
 * The [keepRecentToolMessages] most recent tool results — the model's active working set — are never elided,
 * and user and assistant text is never touched. Compaction produces a fresh message list per call; the loop's
 * stored history is left intact so a retry re-derives the same view.
 */
class HistoryCompactor(
    /** A stale tool result longer than this many characters is truncated; shorter ones are left alone. */
    private val maxToolResultChars: Int = 4_000,
    /** How many of the most-recent tool-result messages to keep verbatim. */
    private val keepRecentToolMessages: Int = 4,
    /** Transcript size (characters) that triggers a compaction pass. */
    private val triggerChars: Int = 120_000,
    /** A triggered pass elides stale results until the transcript is back under this. */
    private val targetChars: Int = 60_000,
) {
    /** Tool calls whose result has been elided. The decision is permanent, so the prefix never drifts back. */
    private val elidedCallIds = HashSet<String>()

    companion object {
        /**
         * A compactor that never elides, for a provider that trims context itself ([LlmProvider.managesContext]
         * [dev.ide.agent.LlmProvider.managesContext]). Two trimmers rewriting the same prompt prefix would each
         * invalidate what the other had just cached.
         */
        fun serverManaged(): HistoryCompactor = HistoryCompactor(triggerChars = Int.MAX_VALUE)
    }

    /** Forgets past elisions. Call when the conversation itself is reset. */
    fun reset() {
        elidedCallIds.clear()
    }

    fun compact(history: List<LlmMessage>): List<LlmMessage> {
        if (size(history) > triggerChars) elideUntilUnderTarget(history)
        if (elidedCallIds.isEmpty()) return history.toList()
        return history.map { m -> if (m.role == LlmRole.TOOL) elide(m) else m }
    }

    /**
     * Marks the oldest stale, oversized tool results as elided until the projected transcript size is back
     * under [targetChars] (or there is nothing left that is worth eliding).
     */
    private fun elideUntilUnderTarget(history: List<LlmMessage>) {
        val toolIndices = history.indices.filter { history[it].role == LlmRole.TOOL }
        if (toolIndices.size <= keepRecentToolMessages) return
        val stale = toolIndices.subList(0, toolIndices.size - keepRecentToolMessages)
        var projected = size(history)
        for (index in stale) {
            if (projected <= targetChars) return
            for (part in history[index].content) {
                if (part !is ContentPart.ToolResultPart) continue
                if (part.toolCallId in elidedCallIds) continue
                if (part.content.length <= maxToolResultChars) continue
                elidedCallIds += part.toolCallId
                projected -= part.content.length - maxToolResultChars
            }
        }
    }

    private fun size(history: List<LlmMessage>): Int = history.sumOf { m ->
        m.content.sumOf { part ->
            when (part) {
                is ContentPart.Text -> part.text.length
                is ContentPart.Thinking -> part.text.length
                is ContentPart.ToolUse -> part.arguments.length + part.name.length
                is ContentPart.ToolResultPart ->
                    if (part.toolCallId in elidedCallIds) minOf(part.content.length, maxToolResultChars)
                    else part.content.length
            }
        }
    }

    private fun elide(message: LlmMessage): LlmMessage {
        val content = message.content.map { part ->
            if (part is ContentPart.ToolResultPart &&
                part.toolCallId in elidedCallIds &&
                part.content.length > maxToolResultChars
            ) {
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
