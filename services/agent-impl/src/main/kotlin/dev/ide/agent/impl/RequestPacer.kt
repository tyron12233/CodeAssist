package dev.ide.agent.impl

/**
 * Keeps an agent run under a provider's per-minute limits by waiting BEFORE a request instead of sending it
 * and collecting a 429.
 *
 * An agentic turn is a burst: one user message can fan out into a dozen requests inside a few seconds, each
 * re-sending the whole context. On a metered free tier that burst is exactly what trips the requests-per-minute
 * and tokens-per-minute caps, and every rejected request is a wasted round trip that also teaches the user the
 * key is "broken". Pacing turns the same work into a slightly slower run that finishes.
 *
 * Limits come from three places, most specific first: values the user configured, values the provider stated in
 * a quota error ([learn]), and, when a per-minute error states nothing, the request rate that was in flight
 * when it tripped (an observed ceiling, so the next minute stays just under it). With no limit known, the pacer
 * never waits.
 *
 * One pacer is shared by every conversation that uses the same key and model, because that is the scope a
 * provider counts against. Thread-safe.
 */
class RequestPacer(
    /** User-configured requests per minute; null or 0 leaves it to what is learned. */
    private val configuredRpm: Int? = null,
    /** User-configured input tokens per minute; null or 0 leaves it to what is learned. */
    private val configuredTpm: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Sent(val atMs: Long, val tokens: Long)

    private val window = ArrayDeque<Sent>()
    private var learnedRpm: Int? = null
    private var learnedTpm: Long? = null

    /** The requests-per-minute ceiling in force, or null when none is known. */
    val rpm: Int? @Synchronized get() = configuredRpm?.takeIf { it > 0 } ?: learnedRpm

    /** The input-tokens-per-minute ceiling in force, or null when none is known. */
    val tpm: Long? @Synchronized get() = configuredTpm?.takeIf { it > 0 } ?: learnedTpm

    /**
     * How long to wait before sending a request of about [estimatedTokens] input tokens, in milliseconds; 0 to
     * send now. Does not record anything: call [record] once the request is actually sent.
     */
    @Synchronized
    fun delayFor(estimatedTokens: Long): Long {
        val now = clock()
        prune(now)
        var waitUntil = now
        rpm?.let { limit ->
            if (window.size >= limit) {
                // The oldest request that has to age out before one more fits.
                waitUntil = maxOf(waitUntil, window.elementAt(window.size - limit).atMs + WINDOW_MS)
            }
        }
        tpm?.let { limit ->
            var used = window.sumOf { it.tokens }
            if (used + estimatedTokens > limit && estimatedTokens <= limit) {
                for (sent in window) {
                    used -= sent.tokens
                    waitUntil = maxOf(waitUntil, sent.atMs + WINDOW_MS)
                    if (used + estimatedTokens <= limit) break
                }
            }
        }
        return (waitUntil - now).coerceAtLeast(0)
    }

    /** Records a request just sent, with its estimated input tokens. */
    @Synchronized
    fun record(estimatedTokens: Long) {
        val now = clock()
        prune(now)
        window.addLast(Sent(now, estimatedTokens))
    }

    /**
     * Learns from a per-minute quota error. A stated limit is adopted as is; without one, the ceiling becomes
     * one less than what was sent in the last minute, so the next window stays under whatever tripped.
     */
    @Synchronized
    fun learn(quota: QuotaInfo?) {
        if (quota?.window == QuotaInfo.Window.DAY) return
        prune(clock())
        val stated = quota?.limit?.takeIf { it > 0 }
        when (quota?.metric) {
            QuotaInfo.Metric.INPUT_TOKENS -> {
                learnedTpm = stated ?: window.sumOf { it.tokens }.takeIf { it > 0 }?.let { it * 9 / 10 }
            }
            else -> {
                learnedRpm = stated?.toInt() ?: (window.size - 1).takeIf { it > 0 }
            }
        }
    }

    private fun prune(now: Long) {
        while (window.isNotEmpty() && window.first().atMs + WINDOW_MS <= now) window.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 60_000L

        /** A rough input-token estimate for a request: ~4 characters per token, images at their usual price. */
        fun estimateTokens(request: dev.ide.agent.LlmRequest): Long {
            var chars = (request.system?.length ?: 0).toLong()
            request.tools.forEach { chars += it.name.length + it.description.length + it.parameters.length }
            var images = 0
            request.messages.forEach { m ->
                m.content.forEach { p ->
                    when (p) {
                        is dev.ide.agent.ContentPart.Text -> chars += p.text.length
                        is dev.ide.agent.ContentPart.Thinking -> chars += p.text.length
                        is dev.ide.agent.ContentPart.ToolUse -> chars += p.arguments.length
                        is dev.ide.agent.ContentPart.ToolResultPart -> { chars += p.content.length; images += p.images.size }
                        is dev.ide.agent.ContentPart.Image -> images++
                    }
                }
            }
            return chars / 4 + images * 1_500L
        }
    }
}
