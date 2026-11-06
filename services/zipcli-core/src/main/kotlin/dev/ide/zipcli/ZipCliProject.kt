package dev.ide.zipcli

import com.zipcli.ke.domain.CommandParser
import com.zipcli.ke.domain.CommandRegistry
import com.zipcli.ke.domain.CommandSpec
import com.zipcli.ke.domain.ShellSession
import com.zipcli.ke.domain.commands.Limits
import com.zipcli.ke.domain.commands.buildArchiveCommands
import com.zipcli.ke.domain.commands.buildContentCommands
import com.zipcli.ke.domain.commands.buildCoreCommands
import com.zipcli.ke.domain.commands.buildScriptCommands
import com.zipcli.ke.domain.script.LineKind
import com.zipcli.ke.domain.script.ScriptIo
import com.zipcli.ke.domain.script.ScriptRunner
import com.zipcli.ke.fs.NioFileSystem
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.zip.Unzipper
import com.zipcli.ke.zip.Zipper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * ZipCLI mounted over one project directory.
 *
 * The project root appears to scripts as [MOUNT] (`/project`); nothing outside it has a virtual path, and `..`
 * cannot climb above it. One instance keeps one shell session (working directory, variables, options), so a
 * `cd` in one [run] is still in effect in the next, exactly like the ZipCLI terminal. Calls are serialized:
 * the session and the file system are not meant for concurrent writers.
 *
 * Runs are non-interactive by default. A command that needs confirmation (a destructive `delete`, an overwrite)
 * stops the batch *before* the prompt and the [Outcome] names it, so a caller (the AI agent) decides whether to
 * repeat the run with `confirm = true`; a caller that can ask a person (the terminal window) passes `onConfirm`
 * to [run] instead. `edit` (the Android terminal's full-screen editor) is unavailable either way.
 *
 * Host-specific commands of the Android app (`saf`, `mcp`) are deliberately not registered here.
 */
class ZipCliProject(root: File) {

    private val rootDir: File = root.canonicalFile
    private val fs: TerminalFileSystem = NioFileSystem(mapOf(MOUNT to rootDir))
    private val specs: List<CommandSpec> by lazy {
        buildCoreCommands { registry } +
            buildContentCommands() +
            buildArchiveCommands(Zipper(fs), Unzipper(fs)) +
            buildScriptCommands()
    }
    private val registry: CommandRegistry by lazy { CommandRegistry(specs) }
    private val parser: CommandParser by lazy { CommandParser(registry) }
    private val shell = ShellSession(MOUNT).also { it.vars["HOME"] = MOUNT }
    private val mutex = Mutex()
    private val history = ArrayList<String>()

    /** The directory this instance is mounted over. */
    val projectRoot: File get() = rootDir

    /** What one [run] produced. [output] is already capped at the requested size. */
    data class Outcome(
        val output: String,
        /** 0 = success; 1 = a command failed or matched nothing; 2 = usage / syntax; 3 = not found; 4 = needs confirmation. */
        val status: Int,
        val cwd: String,
        val truncated: Boolean,
        val timedOut: Boolean,
        /** The command waiting for confirmation when the batch stopped before it, else null. */
        val pendingConfirmation: String?,
        /** Commands after the stopping point that never ran. */
        val notRun: Int,
    ) {
        val ok: Boolean get() = status == 0 && !timedOut
    }

    /** Every command name ZipCLI offers here, for `help`-style listings and completion. */
    fun commandNames(): List<String> = registry.all().map { it.name }

    /** Parses [script] without running or changing anything; returns one line per problem (empty = fine). */
    suspend fun check(script: String): List<String> =
        mutex.withLock { newRunner(CollectingIo(MAX_CHECK_CHARS, null, null)).check(script).problems }

    /**
     * Runs [script] (one command, several, or a multi-line script with `&&`, `||`, `for` loops and heredocs).
     * [cwd] is a virtual path (relative paths resolve against the session's current directory); when it is
     * not an existing directory nothing runs and the outcome says so.
     *
     * Interactive callers (the terminal window) pass [onLine], called with every output line as it is printed
     * (error lines carry their `error: ` prefix), and [onConfirm], which answers a confirmation prompt: with it
     * a destructive step asks and carries on instead of stopping the batch. Without [onConfirm] the run is
     * non-interactive (see the class comment). Pass `maxOutputChars = 0` to stream through [onLine] only.
     */
    suspend fun run(
        script: String,
        confirm: Boolean = false,
        cwd: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        maxOutputChars: Int = Limits.MCP_OUTPUT_CHARS,
        onLine: ((String) -> Unit)? = null,
        onConfirm: (suspend (String) -> Boolean)? = null,
    ): Outcome {
        if (script.isBlank()) return failure("Nothing to run: the command is empty.", 2)
        if (script.length > Limits.MCP_COMMAND_CHARS) {
            return failure("Command is too long (max ${Limits.MCP_COMMAND_CHARS} characters).", 2)
        }
        return mutex.withLock {
            if (cwd != null) {
                val target = com.zipcli.ke.fs.VirtualPath.resolve(shell.cwd, cwd)
                val entry = fs.stat(target)
                if (entry == null || !entry.isDirectory) {
                    return@withLock failure("cwd '$cwd' is not an existing directory ($target).", 3)
                }
                shell.cwd = target
            }
            val io = CollectingIo(maxOutputChars, onLine, onConfirm)
            val runner = newRunner(io)
            synchronized(history) {
                history += script.trim()
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }
            val result = withTimeoutOrNull(timeoutSeconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L) {
                runner.run(script, shell, autoYes = confirm, interactive = false)
            }
            if (result == null) {
                io.print(listOf("Timed out after ${timeoutSeconds}s; the run was cancelled and later commands did not run."), LineKind.ERROR)
            }
            Outcome(
                output = io.text(),
                status = result?.status ?: 1,
                cwd = shell.cwd,
                truncated = io.truncated,
                timedOut = result == null,
                pendingConfirmation = result?.pending?.let { "${it.command} (${it.prompt}; repeat with confirm, or add ${it.flag})" },
                notRun = result?.notRun ?: 0,
            )
        }
    }

    private fun newRunner(io: ScriptIo) =
        ScriptRunner(registry, parser, fs, io, transport = "ide", history = { synchronized(history) { history.toList() } })

    private fun failure(message: String, status: Int) =
        Outcome(message + "\n", status, shell.cwd, truncated = false, timedOut = false, pendingConfirmation = null, notRun = 0)

    /**
     * Collects output into a string (capped) and forwards each line to [onLine] as it is printed. A prompt is
     * answered by [onConfirm] when there is one; without it nobody can answer, so [canPrompt] is false and the
     * runner stops the batch before the prompt instead.
     */
    private class CollectingIo(
        private val cap: Int,
        private val onLine: ((String) -> Unit)?,
        private val onConfirm: (suspend (String) -> Boolean)?,
    ) : ScriptIo {
        private val sb = StringBuilder()
        var truncated = false
            private set

        override val canPrompt: Boolean get() = onConfirm != null

        fun text(): String = sb.toString()

        override fun print(lines: List<String>, kind: LineKind) {
            for (line in lines) {
                val text = if (kind == LineKind.ERROR) "error: $line" else line
                onLine?.invoke(text)
                if (sb.length >= cap) truncated = true else sb.append(text).append('\n')
            }
        }

        override suspend fun confirm(prompt: String): Boolean = onConfirm?.invoke(prompt) ?: false
        override fun clear() {}
        override fun openEditor(path: String, content: String) {}
        override suspend fun requestSafPermission() {}
        override fun progress(message: String?) {}
        override fun cwdChanged(cwd: String) {}
    }

    companion object {
        /** Where the project root appears in ZipCLI's virtual file system. */
        const val MOUNT = "/project"
        const val DEFAULT_TIMEOUT_SECONDS = 60L
        const val MAX_TIMEOUT_SECONDS = 600L
        private const val MAX_HISTORY = 200
        private const val MAX_CHECK_CHARS = 20_000
    }
}
