package dev.ide.agent

/**
 * The agent-level contracts: the permission model that gates mutating tools, and the observable event
 * stream a run produces. The loop itself lives in agent-impl; the host observes [AgentEvent]s to build the
 * chat UI state and implements [AgentPermissionGate] to prompt the user.
 */

/** How aggressively the agent may apply changes. Persisted per project. */
enum class PermissionMode { ASK_EACH, AUTO_ACCEPT, PLAN_ONLY }

/** A pending mutating tool call awaiting authorization. [changes] previews what it would do, when the tool can
 *  say ([AgentTool.preview]), so the prompt can show a diff instead of just a path. */
data class WriteRequest(
    val tool: String,
    val summary: String,
    val path: String? = null,
    val changes: List<FileChange> = emptyList(),
) {
    // The pre-[changes] signatures, kept in the bytecode for plugins compiled against them.
    @Deprecated("Binary compatibility with plugins built against SPI 3.0", level = DeprecationLevel.HIDDEN)
    constructor(tool: String, summary: String, path: String? = null) : this(tool, summary, path, emptyList())

    @Deprecated("Binary compatibility with plugins built against SPI 3.0", level = DeprecationLevel.HIDDEN)
    fun copy(tool: String = this.tool, summary: String = this.summary, path: String? = this.path): WriteRequest =
        WriteRequest(tool, summary, path, changes)
}

/**
 * The single decision point for whether a mutating tool may run. The host implementation encodes the
 * active [mode]: AUTO_ACCEPT authorizes silently, PLAN_ONLY refuses, and ASK_EACH suspends until the user
 * answers a prompt. Read-only tools never reach the gate.
 */
interface AgentPermissionGate {
    val mode: PermissionMode

    /** Returns true if the write may proceed. Suspends for a user decision in ASK_EACH. */
    suspend fun authorize(request: WriteRequest): Boolean
}

/** A gate that authorizes everything, for tests and non-interactive runs. */
object AllowAllGate : AgentPermissionGate {
    override val mode: PermissionMode get() = PermissionMode.AUTO_ACCEPT
    override suspend fun authorize(request: WriteRequest): Boolean = true
}

/** An event emitted while a turn runs. The host folds these into the chat transcript state. */
sealed interface AgentEvent {
    data class UserMessage(val text: String) : AgentEvent
    data class AssistantTextDelta(val text: String) : AgentEvent
    data class AssistantThinkingDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val id: String, val name: String, val displaySummary: String) : AgentEvent
    data class ToolCallFinished(val id: String, val ok: Boolean, val resultSummary: String) : AgentEvent
    data class ToolCallDenied(val id: String, val reason: String) : AgentEvent
    data class TurnCompleted(val stopReason: StopReason, val usage: TokenUsage?) : AgentEvent
    /** [kind] names the failure category when it was categorized (an `LlmErrorKind` name such as
     *  `MODEL_NOT_ON_PLAN` or `DAILY_LIMIT`), so the host can offer the right fix. */
    data class Error(val message: String, val kind: String? = null) : AgentEvent {
        // The pre-[kind] signatures, kept in the bytecode for plugins compiled against them.
        @Deprecated("Binary compatibility with plugins built against SPI 3.0", level = DeprecationLevel.HIDDEN)
        constructor(message: String) : this(message, null)

        @Deprecated("Binary compatibility with plugins built against SPI 3.0", level = DeprecationLevel.HIDDEN)
        fun copy(message: String = this.message): Error = Error(message, kind)
    }

    /**
     * The run is paused before its next request, either because the provider rate-limited it or because the
     * client-side pacer is keeping it under a known limit. [untilEpochMs] is when it resumes; any later event
     * means the wait is over. Cancelling the run cancels the wait.
     */
    data class Waiting(val untilEpochMs: Long, val reason: String) : AgentEvent

    /** Tool call [id] changed these files (recorded so the turn can be reviewed and reverted). */
    data class FilesChanged(val id: String, val changes: List<FileChange>) : AgentEvent

    /** The agent replaced its visible plan for the task. */
    data class TodosUpdated(val todos: List<TodoItem>) : AgentEvent
}

/** One step of the agent's plan. [status] is `pending`, `in_progress` or `completed`. */
data class TodoItem(val content: String, val status: String)

/** Receives [AgentEvent]s from a running loop. */
fun interface AgentEventSink {
    suspend fun emit(event: AgentEvent)
}
