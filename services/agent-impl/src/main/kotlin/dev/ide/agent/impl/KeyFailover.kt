package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * The API keys for one provider, primary first and backups after it in the user's order. Each request goes to
 * the first key that is not sitting out; a key the provider refused for a limit, or rejected, sits out for a
 * while so the next one takes over, and comes back first in line once its time is up.
 */
class ApiKeyPool(keys: List<String>, private val clock: () -> Long = System::currentTimeMillis) {
    val keys: List<String> = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private val until = LongArray(this.keys.size)
    private val reasons = arrayOfNulls<LlmErrorKind>(this.keys.size)

    val size: Int get() = keys.size

    /** The first key that may be used now, or null when every key is sitting out. */
    @Synchronized
    fun next(): Int? {
        val now = clock()
        return keys.indices.firstOrNull { until[it] <= now }
    }

    /** Takes key [index] out of rotation after the provider refused it with [kind]. */
    @Synchronized
    fun bench(index: Int, kind: LlmErrorKind, retryAfterMs: Long?) {
        val wait = when (kind) {
            LlmErrorKind.RATE_LIMIT -> (retryAfterMs ?: RATE_LIMIT_MS).coerceAtLeast(1_000)
            // A rejected key does not heal by itself. It stays out until the keys are edited, which builds a new pool.
            LlmErrorKind.AUTH -> Long.MAX_VALUE / 2
            else -> LONG_LIMIT_MS
        }
        until[index] = clock() + wait
        reasons[index] = kind
    }

    /** How long until the first key is usable again; 0 when one already is. */
    @Synchronized
    fun soonestMs(): Long {
        val now = clock()
        return (until.minOrNull() ?: now).minus(now).coerceAtLeast(0)
    }

    /** Why the key that comes back soonest was taken out, or null when none was. */
    @Synchronized
    fun soonestReason(): LlmErrorKind? = until.indices.minByOrNull { until[it] }?.let { reasons[it] }

    /** How many keys are sitting out for each reason, for the message when all of them are. */
    @Synchronized
    fun benchedByReason(): Map<LlmErrorKind, Int> {
        val now = clock()
        return keys.indices.filter { until[it] > now }.mapNotNull { reasons[it] }.groupingBy { it }.eachCount()
    }

    companion object {
        /** How long a key sits out after a per-minute limit that named no retry delay. */
        const val RATE_LIMIT_MS = 60_000L

        /** How long a key sits out after a daily, billing or not-on-plan limit before it is tried again. The
         *  daily boundary differs per provider, so the key is simply retried an hour later. */
        const val LONG_LIMIT_MS = 60 * 60_000L

        /** The refusals that move a request on to the next key. Anything else (a bad request, a context that is
         *  too long, a network failure) would fail the same way on every key. */
        val ROTATE_ON = setOf(
            LlmErrorKind.RATE_LIMIT,
            LlmErrorKind.DAILY_LIMIT,
            LlmErrorKind.QUOTA,
            LlmErrorKind.MODEL_NOT_ON_PLAN,
            LlmErrorKind.AUTH,
        )
    }
}

/**
 * An [LlmClient] over every key in [pool]. A request that a key's provider refuses before any output arrived is
 * sent again with the next usable key, so a spent or rejected key never interrupts the conversation. Once output
 * has streamed the request is never resent, since the user would see the start of the answer twice.
 *
 * When every key is sitting out the request fails. If the first key back is only rate limited for a short while
 * the failure is a [LlmErrorKind.RATE_LIMIT] carrying that wait, so [AgentLoop] waits it out and carries on;
 * otherwise it says that all the keys are spent. With one key or none this is a plain pass-through.
 */
class KeyFailoverClient(
    private val pool: ApiKeyPool,
    private val clientFor: (String) -> LlmClient,
) : LlmClient {
    private val clients = ConcurrentHashMap<String, LlmClient>()

    private fun client(key: String): LlmClient = clients.getOrPut(key) { clientFor(key) }

    override fun chat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        if (pool.size <= 1) {
            emitAll(client(pool.keys.firstOrNull().orEmpty()).chat(request))
            return@flow
        }
        var last: LlmHttpException? = null
        val tried = HashSet<Int>()
        while (true) {
            val index = pool.next() ?: break
            if (!tried.add(index)) break
            var streamed = false
            var refused: LlmStreamEvent.Failed? = null
            client(pool.keys[index]).chat(request).collect { event ->
                if (event is LlmStreamEvent.Failed && !streamed) {
                    refused = event
                } else {
                    streamed = true
                    emit(event)
                }
            }
            val failed = refused ?: return@flow
            val cause = failed.cause as? LlmHttpException
            val kind = cause?.kind
            if (kind == null || kind !in ApiKeyPool.ROTATE_ON) {
                emit(failed)
                return@flow
            }
            pool.bench(index, kind, cause.retryAfterMs)
            last = cause
        }
        val error = allKeysOut(last)
        emit(LlmStreamEvent.Failed(error.message.orEmpty(), error))
    }

    private fun allKeysOut(last: LlmHttpException?): LlmHttpException {
        val wait = pool.soonestMs()
        val detail = last?.message?.substringAfter('\n', "")?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        if (pool.soonestReason() == LlmErrorKind.RATE_LIMIT && wait <= SHORT_WAIT_MS) {
            return LlmHttpException(
                "All ${pool.size} API keys are rate limited. Try again in ${LlmErrors.humanDelay(wait)}.$detail",
                retryAfterMs = wait,
                retryable = true,
                kind = LlmErrorKind.RATE_LIMIT,
                quota = last?.quota,
            )
        }
        val reasons = pool.benchedByReason().entries.joinToString(", ") { (kind, n) -> "$n ${describe(kind)}" }
        val retry = if (pool.soonestReason() == LlmErrorKind.AUTH) "" else " The first one is tried again in ${LlmErrors.humanDelay(wait)}."
        return LlmHttpException(
            "All ${pool.size} API keys for this provider are unavailable ($reasons).$retry Add another key, or " +
                "check your plan with the provider.$detail",
            kind = last?.kind ?: pool.soonestReason(),
            quota = last?.quota,
        )
    }

    private fun describe(kind: LlmErrorKind): String = when (kind) {
        LlmErrorKind.RATE_LIMIT -> "rate limited"
        LlmErrorKind.DAILY_LIMIT -> "at the daily limit"
        LlmErrorKind.QUOTA -> "out of quota or credit"
        LlmErrorKind.MODEL_NOT_ON_PLAN -> "without this model on the plan"
        LlmErrorKind.AUTH -> "rejected"
        else -> kind.name.lowercase()
    }

    private companion object {
        /** A wait [AgentLoop] will sit through rather than report (its own ceiling on a rate-limit wait). */
        const val SHORT_WAIT_MS = 120_000L
    }
}
