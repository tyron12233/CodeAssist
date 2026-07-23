package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.PermissionMode
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.flow.collect

/**
 * Drives one conversation: request -> stream a turn -> if the model called tools, execute them (gating
 * mutating calls through [gate]) and feed the results back -> repeat until the model stops calling tools or
 * the iteration cap is hit. History is retained across user turns; [reset] starts a fresh conversation.
 *
 * The loop runs in the caller's coroutine, so cancelling that coroutine stops generation and tool work.
 * [systemPrompt] is a supplier so the host can refresh live project context each turn while keeping the
 * grounding prefix stable.
 */
class AgentLoop(
    private val client: LlmClient,
    private val model: String,
    private val tools: AgentToolRegistry,
    private val gate: AgentPermissionGate,
    private val systemPrompt: () -> String,
    private val maxTokens: Int = 8192,
    private val maxIterations: Int = 24,
) {
    private val history = mutableListOf<LlmMessage>()

    fun reset() {
        history.clear()
    }

    suspend fun send(userText: String, sink: AgentEventSink) {
        history += LlmMessage.user(userText)
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
        while (iteration++ < maxIterations) {
            val request = LlmRequest(
                model = model,
                system = systemPrompt(),
                messages = history.toList(),
                tools = tools.specs(),
                maxTokens = maxTokens,
                thinking = true,
            )
            val turn = Turn()
            client.chat(request).collect { event -> turn.consume(event, sink) }

            turn.failure?.let { sink.emit(AgentEvent.Error(it)); return }

            history += LlmMessage.assistant(turn.assistantParts())
            val calls = turn.toolCalls()
            if (calls.isEmpty()) {
                sink.emit(AgentEvent.TurnCompleted(turn.stopReason, turn.usage))
                return
            }

            val results = ArrayList<LlmMessage>(calls.size)
            for (call in calls) results += executeCall(call, sink)
            history += results
        }
        sink.emit(AgentEvent.Error("Stopped after $maxIterations tool iterations without finishing."))
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
            val allowed = gate.authorize(WriteRequest(call.name, summary, args.optString("path")))
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

        val result = runCatching { tool.execute(args) }
            .getOrElse { ToolExecutionResult.error(it.message ?: "tool failed") }
        sink.emit(AgentEvent.ToolCallFinished(call.id, ok = !result.isError, resultSummary = brief(result.content)))
        return LlmMessage.toolResult(call.id, result.content, result.isError)
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
                is LlmStreamEvent.Failed -> failure = event.message
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
