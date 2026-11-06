package dev.ide.zipcli.plugin

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import dev.ide.zipcli.ZipCliProject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The `zipcli` agent tool: runs a ZipCLI command or script over the open project (find, search, replace, list,
 * cat, zip, unzip, copy, move, delete, scripts). The project root is [ZipCliProject.MOUNT] (`/project`).
 *
 * Mutating, so the agent loop puts every call behind the permission gate. It is offered to the in-app chat
 * agent only, never to the MCP server: that server runs under an allow-all gate, and a remote client must
 * not get a delete / overwrite command line through it.
 *
 * Note: ZipCLI writes to disk directly, not through the IDE's workspace, so these changes are not part of a
 * chat turn's checkpoint (Undo does not revert them) and an open editor buffer may need a reload.
 *
 * [root] is read on every call so the tool follows the project the agent is working in; [enabled] is the
 * plugin-enabled check, re-read per call.
 */
class ZipCliAgentTool(
    private val root: () -> Path?,
    private val enabled: () -> Boolean = { true },
) : AgentTool {

    /** One engine (and so one shell session: working directory, variables) per project root. */
    private val projects = ConcurrentHashMap<Path, ZipCliProject>()

    override val spec = ToolSpec(
        name = "zipcli",
        description = "Run ZipCLI commands over the open project: list, find, search, replace, cat, create, update, " +
            "copy, move, delete, zip, unzip, and multi-line scripts. The project root is /project and paths are " +
            "relative to the working directory, which persists between calls. Start with command=\"help\" to list " +
            "the commands; \"help scripting\" explains quoting, chaining (; && ||), variables and for-loops. " +
            "HARD RULES: there are NO pipes, NO redirection, NO command substitution and NO arithmetic; run separate " +
            "lines and use 'for' to loop. Single quotes are literal; for byte-exact multi-line text use a heredoc " +
            "(update f <<'EOF' ... EOF). A script stops at the first failing command. A destructive step that asks " +
            "for confirmation stops the batch BEFORE it and the result names it: repeat with confirm=true only when " +
            "the user clearly asked for that action. Use check_only=true to validate a script without running it. " +
            "Changes are written straight to disk and are not part of the chat's Undo.",
        parameters = toolSchema {
            string("command", "The command or script to run, e.g. \"find TODO src\" or a multi-line script.")
            string("cwd", "Working directory for this and later calls, e.g. \"/project/app\". Optional.", required = false)
            boolean("confirm", "Answer yes to confirmation prompts. Only when the user asked for the destructive action.", required = false)
            boolean("check_only", "Parse and validate the script without running anything.", required = false)
            integer("timeout_seconds", "Give up after this many seconds (default 60, max 600).", required = false)
        },
    )

    override val mutating: Boolean get() = true

    override fun summarize(args: ToolArgs): String {
        val command = args.optString("command").orEmpty()
        val first = command.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(120)
        val more = command.lines().count { it.isNotBlank() } - 1
        val check = if (args.optBoolean("check_only") == true) "(check) " else ""
        return "zipcli: $check$first" + if (more > 0) "  (+$more more line${if (more == 1) "" else "s"})" else ""
    }

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        if (!enabled()) return ToolExecutionResult.error("The ZipCLI plugin is turned off (Settings > Plugins).")
        val command = args.optString("command")
        if (command.isNullOrBlank()) return ToolExecutionResult.error("Missing required argument 'command' (a non-empty string).")
        val rootPath = root() ?: return ToolExecutionResult.error("No project is open.")
        val project = projects.computeIfAbsent(rootPath.toAbsolutePath().normalize()) { ZipCliProject(it.toFile()) }

        if (args.optBoolean("check_only") == true) {
            val problems = project.check(command)
            val text = buildString {
                problems.forEach { appendLine(it) }
                append(if (problems.isEmpty()) "check: OK, nothing was run" else "check: ${problems.size} problem line(s), nothing was run")
            }
            return if (problems.isEmpty()) ToolExecutionResult.ok(text) else ToolExecutionResult.error(text)
        }

        val outcome = project.run(
            script = command,
            confirm = args.optBoolean("confirm") ?: false,
            cwd = args.optString("cwd"),
            timeoutSeconds = args.optInt("timeout_seconds")?.toLong() ?: ZipCliProject.DEFAULT_TIMEOUT_SECONDS,
        )
        val text = buildString {
            append(outcome.output)
            if (outcome.truncated) appendLine("[output truncated; the rest was NOT shown. Narrow the command or use --max/--lines/--offset/--count-only]")
            outcome.pendingConfirmation?.let {
                appendLine("[needs confirmation: $it; ${outcome.notRun} command(s) did not run]")
            }
            append("[exit ${outcome.status} | cwd ${outcome.cwd}]")
        }
        // Exit 1 is "a command failed or matched nothing" (find/search follow the grep convention), and a pending
        // confirmation is an instruction to the model, not a fault: only a timeout or a usage / not-found / internal
        // failure is reported as a tool error.
        val failed = outcome.timedOut || (outcome.status >= 2 && outcome.pendingConfirmation == null)
        return if (failed) ToolExecutionResult.error(text) else ToolExecutionResult.ok(text)
    }
}
