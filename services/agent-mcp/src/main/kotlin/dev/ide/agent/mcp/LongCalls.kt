package dev.ide.agent.mcp

import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.toolSchema
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tool calls that outlast the request that made them.
 *
 * An MCP tool call is one HTTP request, and every hop it crosses gives up after a while: the hosted relay
 * waits two minutes, and clients have limits of their own. A first Android build takes longer than that, so the
 * call failed with a timeout while the build carried on, its result lost.
 *
 * A call is given [maxWaitMs]. If it finishes, the client gets the result as before. If not, the call keeps
 * running and the client is told a job id; `task_status` then waits on that job, a bounded time per call, until
 * it has the result. The work is never cancelled by a client giving up, which is also what a build should do.
 *
 * One instance per server. A finished job is forgotten once its result has been read, or after [KEEP_MS].
 */
internal class LongCalls(private val maxWaitMs: Long) {

    private class Job(val tool: String, val summary: String, val work: Deferred<ToolExecutionResult>, val startedMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val ids = AtomicInteger()

    /** Run [block], answering with its result if it comes within [maxWaitMs] and with a job to ask about if not. */
    fun run(tool: String, summary: String, block: suspend () -> ToolExecutionResult): ToolExecutionResult {
        val work = scope.async {
            try {
                block()
            } catch (t: Throwable) {
                ToolExecutionResult.error("Tool '$tool' failed: ${t.message ?: t::class.simpleName}")
            }
        }
        val done = runBlocking { withTimeoutOrNull(maxWaitMs) { work.await() } }
        if (done != null) return done
        prune()
        val id = "job-${ids.incrementAndGet()}"
        jobs[id] = Job(tool, summary, work, System.currentTimeMillis())
        return ToolExecutionResult.ok(
            "Still running after ${maxWaitMs / 1000}s: $summary. It has not been cancelled. " +
                "Call $STATUS_TOOL with job=\"$id\" to wait for the result; do not start it again.",
        )
    }

    private fun status(id: String, waitMs: Long): ToolExecutionResult {
        val job = jobs[id] ?: return ToolExecutionResult.error(
            "No job \"$id\". A job is forgotten once its result has been read." +
                (jobs.keys.sorted().takeIf { it.isNotEmpty() }?.let { " Jobs still known: ${it.joinToString()}." } ?: ""),
        )
        val done = runBlocking { withTimeoutOrNull(waitMs) { job.work.await() } }
        if (done == null) {
            val seconds = (System.currentTimeMillis() - job.startedMs) / 1000
            return ToolExecutionResult.ok("Still running (${seconds}s so far): ${job.summary}. Call $STATUS_TOOL with job=\"$id\" again.")
        }
        jobs.remove(id)
        return done
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        jobs.entries.removeIf { (_, job) -> job.work.isCompleted && job.startedMs < cutoff }
    }

    /** The `task_status` tool, which belongs to the server rather than to the agent's tool set. */
    fun statusTool(mapper: McpJsonMapper): McpServerFeatures.SyncToolSpecification {
        val schema = toolSchema {
            string("job", "The job id a long-running call returned, e.g. job-1.")
            integer("wait_seconds", "How long to wait for it to finish before answering, up to $MAX_STATUS_WAIT_S. Default $DEFAULT_STATUS_WAIT_S.", required = false)
        }
        val tool = McpSchema.Tool.builder(STATUS_TOOL, mapper.readValue(schema, MAP_TYPE))
            .description(
                "Wait for a long-running call (a build, a run) that returned a job id instead of a result, and return " +
                    "its result once it has finished. Answers \"still running\" if it has not; call it again then.",
            )
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).openWorldHint(false).build())
            .build()
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = MapToolArgs(request.arguments() ?: emptyMap(), mapper)
                val id = args.optString("job")?.trim().orEmpty()
                val wait = (args.optInt("wait_seconds") ?: DEFAULT_STATUS_WAIT_S).coerceIn(0, MAX_STATUS_WAIT_S)
                status(id, wait * 1000L).toCallToolResult()
            }
            .build()
    }

    companion object {
        const val STATUS_TOOL = "task_status"
        private const val DEFAULT_STATUS_WAIT_S = 60
        private const val MAX_STATUS_WAIT_S = 90
        private const val KEEP_MS = 30 * 60_000L
        private val MAP_TYPE = object : io.modelcontextprotocol.json.TypeRef<Map<String, Any>>() {}
    }
}
