package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.PermissionMode
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Drives one conversation: request -> stream a turn -> if the model called tools, execute them (gating
 * mutating calls through [gate]) and feed the results back -> repeat until the model stops calling tools or
 * the iteration cap is hit. History is retained across user turns; [reset] starts a fresh conversation.
 *
 * The loop runs in the caller's coroutine, so cancelling that coroutine stops generation and tool work.
 *
 * The prompt is split by volatility so the provider's cache survives the turn: [systemPrompt] supplies the
 * stable grounding that becomes the request's top-level system prefix, while [sessionContext] supplies the
 * per-turn operator state (permission mode, live project context) as a trailing system message after the
 * history — refreshed every iteration without disturbing a single cached byte ahead of it.
 */
class AgentLoop(
    private val client: LlmClient,
    private val model: String,
    private val tools: AgentToolRegistry,
    private val gate: AgentPermissionGate,
    private val systemPrompt: () -> String,
    /** Per-turn operator state, appended after the history as a system message. Null or blank sends nothing. */
    private val sessionContext: () -> String? = { null },
    private val maxTokens: Int = 8192,
    private val maxIterations: Int = 24,
    /** Provider reasoning-token cap forwarded to each request; null leaves the model default. */
    private val thinkingBudget: Int? = null,
    /** Offer the provider's native web search each request (providers without one ignore it). */
    private val webSearch: Boolean = false,
    /** How hard the model should think ([dev.ide.agent.LlmEffort]); null leaves the provider default. */
    private val effort: String? = null,
    /** Trims re-sent tool output so a long task does not re-bill the whole transcript each step. */
    private val compactor: HistoryCompactor = HistoryCompactor(),
    /** Keeps requests under the provider's per-minute limits; null sends as fast as the loop runs. */
    private val pacer: RequestPacer? = null,
    /** Records what each mutating call changed, for diffs and undo; null records nothing. */
    private val checkpoints: CheckpointWorkspace? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        /** How many rate-limit waits one turn may sit through before it gives up and reports the error. */
        const val MAX_RATE_LIMIT_WAITS = 6

        /** The wait when a rate limit names no retry delay. */
        const val DEFAULT_RATE_LIMIT_WAIT_MS = 30_000L

        /** A provider asking for longer than this is reported rather than waited out. */
        const val MAX_RATE_LIMIT_WAIT_MS = 120_000L

        /** Sent on the final turn once the iteration cap is hit; no tools are offered alongside it. */
        const val COMPACT = "Summarize this conversation so far so it can continue from your summary alone. " +
            "Keep: the user's goals and constraints, decisions made, files created or changed (with paths), the " +
            "current state of the work, open problems, and the next steps. Be specific and concise. Reply with " +
            "the summary only."

        const val WRAP_UP = "You have reached this task's tool-call limit, so this is your last turn and no " +
            "tools are available. Do not start new work. Report what you changed, what you verified, and " +
            "exactly what is left to do, so the user can pick it up from here."
    }

    private val history = mutableListOf<LlmMessage>()

    fun reset() {
        history.clear()
        compactor.reset()
    }

    /** The conversation so far, so a rebuilt loop can carry it over (see [restore]). */
    fun snapshot(): List<LlmMessage> = history.toList()

    /**
     * Adopts a conversation captured by [snapshot]. Changing a setting rebuilds the loop, and without this the
     * model would silently start from nothing while the on-screen transcript still showed the whole thread.
     *
     * [dropThinking] drops reasoning blocks, which must be set when the model or provider changed: a thinking
     * block's signature is bound to the model that produced it, so replaying one to a different model is at
     * best ignored and at worst rejected.
     */
    fun restore(messages: List<LlmMessage>, dropThinking: Boolean) {
        history.clear()
        compactor.reset()
        messages.mapTo(history) { m ->
            if (!dropThinking) m else m.copy(content = m.content.filter { it !is ContentPart.Thinking })
        }
    }

    suspend fun send(userText: String, sink: AgentEventSink, images: List<ContentPart.Image> = emptyList()) {
        history += if (images.isEmpty()) LlmMessage.user(userText) else LlmMessage.user(userText, images)
        sink.emit(AgentEvent.UserMessage(userText))
        runTurns(sink)
    }

    /** True when there is a conversation to resume (used to offer a retry after a failure). */
    fun canResume(): Boolean = history.isNotEmpty()

    /** Re-run the conversation from the current history after a transient failure, WITHOUT adding a new user
     *  turn (a failed turn leaves the user message, and any completed tool results, in place). No-op when
     *  there's nothing to resume. */
    suspend fun retry(sink: AgentEventSink) {
        if (history.isEmpty()) return
        runTurns(sink)
    }

    private suspend fun runTurns(sink: AgentEventSink) {
        var iteration = 0
        var rateLimitWaits = 0
        // A user-visible "turn" is the whole loop, which is several requests; report what all of them cost.
        var total = TokenUsage()
        while (iteration++ < maxIterations) {
            val request = LlmRequest(
                model = model,
                system = systemPrompt(),
                messages = withSessionContext(compactor.compact(history)),
                tools = tools.specs(),
                maxTokens = maxTokens,
                thinking = true,
                thinkingBudget = thinkingBudget,
                webSearch = webSearch,
                effort = effort,
            )
            pace(request, sink)
            val turn = Turn()
            client.chat(request).collect { event -> turn.consume(event, sink) }

            val failure = turn.failure
            if (failure != null) {
                val wait = rateLimitWait(turn)
                if (wait != null && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                    // Nothing reached the user, so the same request can simply be sent again once the window
                    // has moved on. The attempt does not count against the iteration cap.
                    rateLimitWaits++
                    iteration--
                    sink.emit(AgentEvent.Waiting(clock() + wait, "Rate limited by the provider. Retrying when the limit resets."))
                    delay(wait)
                    continue
                }
                sink.emit(AgentEvent.Error(failure, (turn.failureCause as? LlmHttpException)?.kind?.name))
                return
            }
            rateLimitWaits = 0
            turn.usage?.let { total += it }

            history += LlmMessage.assistant(turn.assistantParts())
            val calls = turn.toolCalls()
            if (calls.isEmpty()) {
                sink.emit(AgentEvent.TurnCompleted(turn.stopReason, total))
                return
            }

            history += executeCalls(calls, sink)
        }
        wrapUp(sink, total)
    }

    /**
     * The iteration cap has been reached. Rather than throwing the run away with an error — which bills the
     * whole task and returns nothing — spend one more turn, with no tools offered so it cannot start more work,
     * asking the model to report what it did and what is left. The user gets a usable hand-off.
     */
    private suspend fun wrapUp(sink: AgentEventSink, usageSoFar: TokenUsage) {
        val request = LlmRequest(
            model = model,
            system = systemPrompt(),
            messages = compactor.compact(history) + LlmMessage(LlmRole.SYSTEM, listOf(ContentPart.Text(WRAP_UP))),
            tools = emptyList(),
            maxTokens = maxTokens,
            thinking = true,
            thinkingBudget = thinkingBudget,
            webSearch = false,
            effort = effort,
        )
        pace(request, sink)
        val turn = Turn()
        client.chat(request).collect { event -> turn.consume(event, sink) }
        turn.failure?.let {
            sink.emit(AgentEvent.Error("Stopped after $maxIterations tool iterations without finishing."))
            return
        }
        history += LlmMessage.assistant(turn.assistantParts())
        val total = turn.usage?.let { usageSoFar + it } ?: usageSoFar
        sink.emit(AgentEvent.TurnCompleted(turn.stopReason, total))
    }

    /**
     * Replaces the conversation with a model-written summary of it, so a long task can continue without
     * re-sending (and re-billing) everything so far. The summary streams to [sink] like an answer. Returns false,
     * leaving the history untouched, when there is nothing to compact or the request failed.
     */
    suspend fun compactConversation(sink: AgentEventSink): Boolean {
        if (history.isEmpty()) return false
        val request = LlmRequest(
            model = model,
            system = systemPrompt(),
            messages = compactor.compact(history) + LlmMessage.user(COMPACT),
            tools = emptyList(),
            maxTokens = maxTokens,
            thinking = false,
            effort = effort,
        )
        pace(request, sink)
        val turn = Turn()
        client.chat(request).collect { event -> turn.consume(event, sink) }
        val failure = turn.failure
        if (failure != null || turn.text.isBlank()) {
            sink.emit(AgentEvent.Error(failure ?: "The model returned an empty summary.", (turn.failureCause as? LlmHttpException)?.kind?.name))
            return false
        }
        history.clear()
        compactor.reset()
        history += LlmMessage.user("Continue the earlier conversation. Here is where it got to.")
        history += LlmMessage.assistant(listOf(ContentPart.Text(turn.text.toString())))
        sink.emit(AgentEvent.TurnCompleted(turn.stopReason, turn.usage))
        return true
    }

    /** Waits, visibly, when sending [request] now would break a known per-minute limit, then records it. */
    private suspend fun pace(request: LlmRequest, sink: AgentEventSink) {
        val p = pacer ?: return
        val estimate = RequestPacer.estimateTokens(request)
        val wait = p.delayFor(estimate)
        if (wait > 0) {
            val limit = listOfNotNull(
                p.rpm?.let { "$it requests" },
                p.tpm?.let { "$it tokens" },
            ).joinToString(" and ")
            sink.emit(AgentEvent.Waiting(clock() + wait, "Pacing requests to stay under the provider's limit of $limit per minute."))
            delay(wait)
        }
        p.record(estimate)
    }

    /**
     * How long to wait before resending a turn that failed on a per-minute rate limit, or null when the
     * failure is not one to wait out: a different error, a daily or not-on-plan quota (waiting does not help),
     * a wait longer than [MAX_RATE_LIMIT_WAIT_MS], or a turn that already streamed something to the user.
     */
    private fun rateLimitWait(turn: Turn): Long? {
        val cause = turn.failureCause as? LlmHttpException ?: return null
        if (cause.kind != LlmErrorKind.RATE_LIMIT || turn.producedOutput) return null
        pacer?.learn(cause.quota)
        val wait = cause.retryAfterMs ?: DEFAULT_RATE_LIMIT_WAIT_MS
        if (wait > MAX_RATE_LIMIT_WAIT_MS) return null
        return wait.coerceAtLeast(1_000)
    }

    /**
     * Appends the per-turn operator state as a trailing system message. It is built fresh each iteration and
     * never stored in [history], so it stays exactly one message long and always sits after everything the
     * provider has already cached.
     */
    private fun withSessionContext(messages: List<LlmMessage>): List<LlmMessage> {
        val context = sessionContext()?.takeIf { it.isNotBlank() } ?: return messages
        // An operator instruction has to follow a user turn (a tool-result run counts as one), so a history
        // left mid-turn by a cancelled run gets no session context rather than a rejected request.
        val last = messages.lastOrNull()?.role ?: return messages
        if (last != LlmRole.USER && last != LlmRole.TOOL) return messages
        return messages + LlmMessage(LlmRole.SYSTEM, listOf(ContentPart.Text(context)))
    }

    /**
     * Runs the turn's tool calls, preserving their order in the returned results. Read-only calls run
     * concurrently (a turn that reads several files pays one file's latency, not the sum); mutating and
     * unknown calls run sequentially afterward so their permission prompts never race and writes stay
     * ordered and deterministic.
     */
    private suspend fun executeCalls(calls: List<ContentPart.ToolUse>, sink: AgentEventSink): List<LlmMessage> {
        if (calls.size == 1) return listOf(executeCall(calls[0], sink))
        val results = arrayOfNulls<LlmMessage>(calls.size)
        coroutineScope {
            calls.forEachIndexed { i, call ->
                val tool = tools.find(call.name)
                if (tool != null && !tool.mutating) {
                    launch { results[i] = executeCall(call, sink) }
                }
            }
        }
        calls.forEachIndexed { i, call ->
            if (results[i] == null) results[i] = executeCall(call, sink)
        }
        return results.map { it!! }
    }

    private suspend fun executeCall(call: ContentPart.ToolUse, sink: AgentEventSink): LlmMessage {
        val tool = tools.find(call.name)
        val args = JsonToolArgs(parseArgsObject(call.arguments))
        if (tool == null) {
            sink.emit(AgentEvent.ToolCallStarted(call.id, call.name, call.name))
            sink.emit(AgentEvent.ToolCallFinished(call.id, ok = false, resultSummary = "unknown tool"))
            return LlmMessage.toolResult(call.id, "Error: unknown tool '${call.name}'.", isError = true)
        }

        val summary = runCatching { tool.summarize(args) }.getOrDefault(call.name)
        sink.emit(AgentEvent.ToolCallStarted(call.id, call.name, summary))

        if (tool.mutating) {
            // Ask with the change itself in hand when the tool can say what it would do, so the prompt shows a
            // diff rather than only a path. Skipped in modes that never ask.
            val preview = if (gate.mode == PermissionMode.ASK_EACH) runCatching { tool.preview(args) }.getOrDefault(emptyList()) else emptyList()
            val allowed = gate.authorize(WriteRequest(call.name, summary, args.optString("path"), preview))
            if (!allowed) {
                val reason = when (gate.mode) {
                    PermissionMode.PLAN_ONLY ->
                        "Plan-only mode is active, so file changes are disabled. Describe the change instead of applying it."
                    else -> "The user declined this change."
                }
                sink.emit(AgentEvent.ToolCallDenied(call.id, reason))
                return LlmMessage.toolResult(call.id, "Denied: $reason", isError = true)
            }
        }

        // Mutating calls run one at a time, so the checkpoint workspace can attribute every write to this call.
        if (tool.mutating) checkpoints?.beginCall(call.id)
        val result = runCatching { tool.execute(args) }
            .getOrElse { ToolExecutionResult.error(it.message ?: "tool failed") }
        if (tool.mutating) {
            checkpoints?.endCall()?.takeIf { it.isNotEmpty() }?.let { sink.emit(AgentEvent.FilesChanged(call.id, it)) }
        }
        result.event?.let { sink.emit(it) }
        sink.emit(AgentEvent.ToolCallFinished(call.id, ok = !result.isError, resultSummary = brief(result.content)))
        return LlmMessage.toolResult(call.id, result.content, result.isError, result.images)
    }

    private fun brief(content: String): String {
        val firstLine = content.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.length > 160) firstLine.take(157) + "..." else firstLine
    }

    /** Accumulates a single streamed turn into an assistant message plus the tool calls to run. */
    private class Turn {
        val text = StringBuilder()
        private val thinkingParts = ArrayList<ContentPart.Thinking>()
        private val toolOrder = ArrayList<String>()
        private val toolById = HashMap<String, ContentPart.ToolUse>()
        var usage: TokenUsage? = null
        var stopReason: StopReason = StopReason.END_TURN
        var failure: String? = null
        var failureCause: Throwable? = null

        /** Whether anything from this turn has reached the user (so resending it would duplicate output). */
        val producedOutput: Boolean get() = text.isNotEmpty() || toolOrder.isNotEmpty() || thinkingParts.isNotEmpty()

        suspend fun consume(event: LlmStreamEvent, sink: AgentEventSink) {
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    text.append(event.text)
                    sink.emit(AgentEvent.AssistantTextDelta(event.text))
                }
                is LlmStreamEvent.ThinkingDelta -> sink.emit(AgentEvent.AssistantThinkingDelta(event.text))
                is LlmStreamEvent.ThinkingCompleted -> thinkingParts += ContentPart.Thinking(event.text, event.signature)
                is LlmStreamEvent.ToolCallCompleted -> {
                    if (event.id !in toolById) toolOrder += event.id
                    toolById[event.id] = ContentPart.ToolUse(event.id, event.name, event.arguments, event.signature)
                }
                is LlmStreamEvent.Usage -> usage = event.usage
                is LlmStreamEvent.Completed -> stopReason = event.stopReason
                is LlmStreamEvent.Failed -> {
                    failure = event.message
                    failureCause = event.cause
                }
                is LlmStreamEvent.ToolCallStarted, is LlmStreamEvent.ToolCallArgsDelta -> Unit
            }
        }

        fun toolCalls(): List<ContentPart.ToolUse> = toolOrder.mapNotNull { toolById[it] }

        fun assistantParts(): List<ContentPart> {
            val parts = ArrayList<ContentPart>()
            parts += thinkingParts
            if (text.isNotEmpty()) parts += ContentPart.Text(text.toString())
            parts += toolCalls()
            return parts
        }
    }
}
