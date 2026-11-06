package com.zipcli.ke.domain.script

import com.zipcli.ke.domain.CommandContext
import com.zipcli.ke.domain.CommandParseException
import com.zipcli.ke.domain.CommandParser
import com.zipcli.ke.domain.CommandRegistry
import com.zipcli.ke.domain.CommandResult
import com.zipcli.ke.domain.ErrorCodes
import com.zipcli.ke.domain.Glob
import com.zipcli.ke.domain.Lexer
import com.zipcli.ke.domain.ParseOutcome
import com.zipcli.ke.domain.ScriptCheck
import com.zipcli.ke.domain.ScriptLauncher
import com.zipcli.ke.domain.ShellSession
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class LineKind { OUTPUT, ERROR, INFO, TRACE }

/** Everything the runner needs from the outside world (implemented by the ViewModel, or by tests). */
interface ScriptIo {
    fun print(lines: List<String>, kind: LineKind)
    suspend fun confirm(prompt: String): Boolean
    fun clear()
    fun openEditor(path: String, content: String)
    suspend fun requestSafPermission()
    fun progress(message: String?)
    fun cwdChanged(cwd: String)

    /**
     * False when nobody can answer a confirmation prompt (a remote MCP call). The runner then stops the batch
     * *before* the prompt and reports it as a pending confirmation instead of guessing "no" and carrying on.
     */
    val canPrompt: Boolean get() = true
}

/**
 * Runs typed input and script files. One runner = one terminal; it runs one thing at a time.
 *
 * Semantics (documented for users in `help scripting`):
 *  - statements run top to bottom; `a && b` runs b only if a succeeded, `a || b` only if a failed
 *  - by default the first failing command stops the script (`set +e` to keep going)
 *  - `for NAME in items; do ...; done` loops, with wildcards expanded in the item list
 *  - `run file [args]` executes a script in its own sub-session
 */
class ScriptRunner(
    private val registry: CommandRegistry,
    private val parser: CommandParser,
    private val fs: TerminalFileSystem,
    private val io: ScriptIo,
    private val transport: String = "terminal",
    private val history: () -> List<String> = { emptyList() },
) : ScriptLauncher {

    /** A command that needed a confirmation nobody could give (see [ScriptIo.canPrompt]). */
    data class Pending(val line: Int, val command: String, val prompt: String, val flag: String = "--yes")

    /** One executed command, for `--report json` style callers. [status] follows the exit-status table. */
    data class Report(val line: Int, val command: String, val status: Int, val message: String?)

    data class Result(
        val status: Int,
        val executed: Int,
        val failed: Int,
        val abortedAtLine: Int?,
        val pending: Pending? = null,
        /** Commands after the stopping point that never ran (only counted when the batch stopped early). */
        val notRun: Int = 0,
        val reports: List<Report> = emptyList(),
    )

    private class ExitSignal(val code: Int) : RuntimeException()

    private class Run(
        val session: ShellSession,
        val interactive: Boolean,
        val autoYes: Boolean,
        val multi: Boolean,
    ) {
        var executed = 0
        var failed = 0
        var aborted = false
        var abortLine = 0
        var pending: Pending? = null
        var lastMessage: String? = null
        val reports = ArrayList<Report>()
        val lookup: (String) -> String? = { name -> session.lookup(name) }
    }

    private var steps = 0

    // ───────────────────────── entry points ─────────────────────────

    /**
     * Runs text typed at the prompt (one command, several, or a pasted script).
     * [interactive] = false makes `edit` / `saf grant` fail instead of opening UI (used by the MCP server).
     */
    suspend fun run(text: String, session: ShellSession, autoYes: Boolean = false, interactive: Boolean = true): Result {
        steps = 0
        val items = try { ScriptParser.parse(text) } catch (e: ScriptSyntaxException) {
            val rendered = e.render().toMutableList()
            rendered[0] = ErrorCodes.tag(ErrorCodes.USAGE) + " " + rendered[0]
            io.print(rendered, LineKind.ERROR)
            return Result(ErrorCodes.STATUS_USAGE, 0, 0, e.line)
        }
        if (items.isEmpty()) return Result(0, 0, 0, null)
        return execute(items, Run(session, interactive = interactive, autoYes = autoYes || session.autoYes, multi = isMulti(items)), null)
    }

    // ───────────────────────── check (dry run) ─────────────────────────

    /**
     * Validates a script without running or changing anything: quoting, heredocs, unknown commands, unknown
     * flags and wrong argument counts. Every problem is reported at once, with its physical line number.
     */
    override suspend fun check(text: String): ScriptCheck {
        val items = try { ScriptParser.parse(text) } catch (e: ScriptSyntaxException) {
            val r = e.render().toMutableList()
            r[0] = ErrorCodes.tag(ErrorCodes.USAGE) + " " + r[0]
            return ScriptCheck(r, 0)
        }
        val problems = ArrayList<String>()
        var commands = 0
        fun walk(list: List<Item>) {
            for (item in list) {
                when (val node = item.node) {
                    is CmdNode -> { commands++; checkCommand(node, problems) }
                    is ForNode -> {
                        try { Lexer.words(node.itemsRaw) } catch (e: CommandParseException) {
                            problems += "line ${node.line}: ${ErrorCodes.tag(ErrorCodes.USAGE)} ${e.message}"
                        }
                        walk(node.body)
                    }
                }
            }
        }
        walk(items)
        return ScriptCheck(problems, commands)
    }

    private fun checkCommand(node: CmdNode, problems: MutableList<String>) {
        fun problem(msg: String) {
            problems += "line ${node.line}: ${ErrorCodes.tag(ErrorCodes.classify(msg).let { if (it == ErrorCodes.FAILED) ErrorCodes.USAGE else it })} $msg"
        }
        val words = try { Lexer.words(parser.autoFix(node.raw)) } catch (e: CommandParseException) {
            problem(e.message ?: "parse error"); return
        }
        if (words.isEmpty()) return
        val tokens = words.map { it.text }.toMutableList()
        val spec = registry.find(tokens[0])
        if (spec != null && spec.mayConfirm) tokens.removeAll { it == "--yes" || it == "-y" }
        if (node.heredoc != null) {
            if (spec != null && spec.flags.none { it.long == "content" }) {
                problem("a heredoc (<<EOF) can only feed create and update; '${spec.name}' does not take a body"); return
            }
            if (tokens.any { it == "--content" || it == "-c" || it.startsWith("--content=") }) {
                problem("a heredoc and --content were both given; use one"); return
            }
            tokens += listOf("--content", node.heredoc)
        }
        when (val o = parser.parseTokens(tokens, node.raw)) {
            is ParseOutcome.Err -> if (o.message != "empty") problem(o.message)
            is ParseOutcome.Ok -> {}
        }
    }

    override suspend fun runFile(parent: ShellSession, path: String, args: List<String>, autoYes: Boolean): Int {
        if (parent.depth >= MAX_DEPTH) {
            io.print(listOf("run: scripts nested more than $MAX_DEPTH deep (recursion?)"), LineKind.ERROR)
            return 1
        }
        val text = try { fs.readText(path) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            io.print(listOf("Cannot read $path: ${e.message ?: e.javaClass.simpleName}"), LineKind.ERROR)
            return 1
        }.removePrefix("﻿")

        val child = parent.child()
        child.scriptName = VirtualPath.name(path)
        for ((i, a) in args.withIndex()) child.vars["${i + 1}"] = a
        child.vars["#"] = args.size.toString()

        val items = try { ScriptParser.parse(text) } catch (e: ScriptSyntaxException) {
            val r = e.render().toMutableList()
            r[0] = "${child.scriptName}: " + ErrorCodes.tag(ErrorCodes.USAGE) + " " + r[0]
            io.print(r, LineKind.ERROR)
            return ErrorCodes.STATUS_USAGE
        }
        return execute(items, Run(child, interactive = false, autoYes = autoYes, multi = true), child.scriptName).status
    }

    // ───────────────────────── execution ─────────────────────────

    private suspend fun execute(items: List<Item>, run: Run, scriptName: String?): Result {
        val started = System.currentTimeMillis()
        var status = 0
        try {
            status = execList(items, run)
        } catch (e: ExitSignal) {
            status = e.code
        } finally {
            io.progress(null)
        }
        val pending = run.pending
        val notRun = if (run.aborted) commandsAfter(items, run.abortLine) else 0
        if (run.multi && run.executed > 1 || (run.aborted && run.multi)) {
            val secs = (System.currentTimeMillis() - started) / 1000.0
            val who = scriptName?.let { "$it: " } ?: ""
            val time = String.format("%.1fs", secs)
            when {
                pending != null -> io.print(listOf(
                    "${who}stopped at line ${pending.line}: 1 command needs confirmation, " +
                        "$notRun command(s) not run ($time). Re-issue with ${pending.flag} on line ${pending.line} " +
                        "(or confirm=true over MCP) to authorise it."), LineKind.ERROR)
                run.aborted -> io.print(
                    listOf("${who}stopped at line ${run.abortLine} after ${run.executed} command(s) ($time)" +
                        if (notRun > 0) ", $notRun command(s) not run" else ""), LineKind.ERROR)
                run.failed > 0 -> io.print(
                    listOf("${who}${run.executed} commands, ${run.failed} failed ($time)"), LineKind.ERROR)
                else -> io.print(listOf("${who}${run.executed} commands OK ($time)"), LineKind.INFO)
            }
        }
        if (pending != null) status = ErrorCodes.STATUS_NEEDS_CONFIRM
        return Result(status, run.executed, run.failed, if (run.aborted) run.abortLine else null,
            pending, notRun, run.reports.toList())
    }

    /** Commands that sit after [line] in the script text (a loop body counts once): what a stopped batch never ran. */
    private fun commandsAfter(items: List<Item>, line: Int): Int {
        var n = 0
        for (item in items) {
            when (val node = item.node) {
                is CmdNode -> if (node.line > line) n++
                is ForNode -> n += commandsAfter(node.body, line)
            }
        }
        return n
    }

    private fun isMulti(items: List<Item>) = items.size > 1 || items.first().node is ForNode

    private suspend fun execList(items: List<Item>, run: Run): Int {
        var status = 0
        var prev: Connector? = null
        for (item in items) {
            val go = when (prev) {
                null, Connector.SEQ -> true
                Connector.AND -> status == 0
                Connector.OR -> status != 0
            }
            if (go) {
                coroutineContext.ensureActive()
                status = when (val node = item.node) {
                    is CmdNode -> execCommand(node, run)
                    is ForNode -> execFor(node, run)
                }
                run.session.lastStatus = status
                if (run.aborted) return status
                val unhandled = item.next == null || item.next == Connector.SEQ
                if (status != 0 && run.session.errexit && unhandled) {
                    run.aborted = true
                    run.abortLine = item.node.line
                    return status
                }
            }
            prev = item.next
        }
        return status
    }

    private suspend fun execFor(node: ForNode, run: Run): Int {
        val session = run.session
        val words = try { Lexer.words(node.itemsRaw, run.lookup) } catch (e: CommandParseException) {
            return fail(run, node.line, e.message ?: "parse error")
        }
        val values = ArrayList<String>()
        for (w in words) {
            if (w.glob) values += Glob.expand(fs, session.cwd, w.text) else values += w.text
        }
        var status = 0
        for (v in values) {
            coroutineContext.ensureActive()
            session.vars[node.variable] = v
            status = execList(node.body, run)
            if (run.aborted) break
        }
        return status
    }

    private suspend fun execCommand(node: CmdNode, run: Run): Int {
        run.lastMessage = null
        val status = execCommandInner(node, run)
        if (run.reports.size < MAX_REPORTS) {
            run.reports += Report(node.line, node.raw.lineSequence().first().trim().take(200), status, run.lastMessage)
        }
        return status
    }

    private suspend fun execCommandInner(node: CmdNode, run: Run): Int {
        val session = run.session
        if (++steps > MAX_STEPS) {
            run.aborted = true; run.abortLine = node.line
            io.print(listOf("Script exceeded $MAX_STEPS commands; stopping."), LineKind.ERROR)
            return 1
        }

        val words = try { Lexer.words(parser.autoFix(node.raw), run.lookup) } catch (e: CommandParseException) {
            return fail(run, node.line, e.message ?: "parse error")
        }
        if (words.isEmpty()) return 0
        if (session.trace) io.print(listOf("+ " + words.joinToString(" ") { quoteForTrace(it.text) }), LineKind.TRACE)

        val spec = registry.find(words[0].text)
        var tokens = words.map { it.text }

        // Global --yes / -y: authorises this one command's confirmation (only for commands that can ask).
        var cmdYes = false
        if (spec != null && spec.mayConfirm && spec.flags.none { it.long == "yes" }) {
            val stripped = tokens.filter { it != "--yes" && it != "-y" }
            if (stripped.size != tokens.size) { cmdYes = true; tokens = stripped }
        }

        if (spec != null && spec.expandGlobs) {
            val expanded = ArrayList<String>()
            expanded += tokens[0]
            var k = 1
            for (w in words.drop(1)) {
                if (w.text == "--yes" || w.text == "-y") { if (cmdYes) continue }
                if (w.glob) {
                    val m = Glob.expand(fs, session.cwd, w.text)
                    if (m.isEmpty()) return fail(run, node.line, "No files match: ${w.text}")
                    expanded += m
                } else expanded += w.text
                k++
            }
            tokens = expanded
        }

        // Heredoc body: byte-exact, handed to the command as its --content value (never re-lexed).
        if (node.heredoc != null) {
            if (spec == null) return fail(run, node.line, unknownCommandText(words[0].text))
            if (spec.flags.none { it.long == "content" })
                return fail(run, node.line, "A heredoc (<<EOF) can only feed create and update; '${spec.name}' does not take a body.")
            if (tokens.any { it == "--content" || it == "-c" || it.startsWith("--content=") })
                return fail(run, node.line, "Usage: give the body either as a heredoc or with --content, not both.")
            tokens = tokens + listOf("--content", node.heredoc)
        }

        val parsed = when (val outcome = parser.parseTokens(tokens, node.raw)) {
            is ParseOutcome.Err -> return if (outcome.message == "empty") 0 else fail(run, node.line, outcome.message)
            is ParseOutcome.Ok -> outcome.parsed
        }
        if (spec == null) return fail(run, node.line, "Unknown command: ${parsed.name}")

        val ctx = CommandContext(
            parsed = parsed, fs = fs, cwd = session.cwd, history = history(),
            session = session, launcher = this, transport = transport, progress = { io.progress(it) },
        )
        run.executed++

        var result: CommandResult = invoke { spec.execute(ctx) }
        while (true) {
            when (val r = result) {
                is CommandResult.Success -> {
                    r.newCwd?.let { setCwd(session, it) }
                    if (r.lines.isNotEmpty()) io.print(r.lines, LineKind.OUTPUT)
                    return r.status
                }
                is CommandResult.Failure -> return fail(run, node.line, r.message, r.code)
                is CommandResult.Confirmation -> {
                    io.progress(null)
                    val yes = run.autoYes || session.autoYes || cmdYes
                    if (!yes && !io.canPrompt) {
                        // Nobody can answer: stop the whole batch here, before the prompt, and say so precisely.
                        val p = Pending(node.line, node.raw.lineSequence().first().trim().take(200), r.prompt)
                        run.pending = p
                        run.aborted = true
                        run.abortLine = node.line
                        val msg = "line ${node.line}: ${ErrorCodes.tag(ErrorCodes.NEEDS_CONFIRM)} confirmation required: " +
                            "${r.prompt} Not run. Authorise it with ${p.flag} on this command (or confirm=true over MCP)."
                        run.lastMessage = msg
                        io.print(listOf(msg), LineKind.ERROR)
                        return ErrorCodes.STATUS_NEEDS_CONFIRM
                    }
                    if (!(yes || io.confirm(r.prompt))) {
                        io.print(listOf("Cancelled."), LineKind.INFO)
                        return 1
                    }
                    result = invoke { r.onConfirm() }
                }
                CommandResult.Clear -> { io.clear(); return 0 }
                is CommandResult.OpenEditor ->
                    return if (run.interactive) { io.openEditor(r.path, r.initialContent); 0 }
                    else fail(run, node.line, "'${parsed.name}' opens the editor and can't run inside a script (use update --content)")
                CommandResult.RequestSafPermission ->
                    return if (run.interactive) { io.requestSafPermission(); 0 }
                    else fail(run, node.line, "'saf grant' needs the system picker and can't run inside a script")
                is CommandResult.Exit -> throw ExitSignal(r.code)
            }
        }
    }

    private fun unknownCommandText(name: String): String = parser.parse(name).let { (it as? ParseOutcome.Err)?.message ?: "Unknown command: $name" }

    private suspend fun invoke(block: suspend () -> CommandResult): CommandResult =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExitSignal) {
            throw e
        } catch (e: java.util.regex.PatternSyntaxException) {
            // A caller's own bad regex is caught inside find/replace ("Invalid pattern"). Reaching this point
            // means a pattern the program built itself failed to compile: a bug, never the script's fault.
            CommandResult.Failure(internalMessage(e), ErrorCodes.INTERNAL)
        } catch (e: java.io.IOException) {
            CommandResult.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: com.zipcli.ke.fs.FsException) {
            CommandResult.Failure(e.message ?: "file system error")
        } catch (e: SecurityException) {
            CommandResult.Failure(e.message ?: "permission denied")
        } catch (e: IllegalArgumentException) {
            CommandResult.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            CommandResult.Failure(internalMessage(e), ErrorCodes.INTERNAL)
        }

    private fun internalMessage(e: Exception): String {
        val where = e.stackTrace.firstOrNull { it.className.startsWith("com.zipcli") }
            ?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
        return "Internal error: ${e.javaClass.simpleName}: ${e.message ?: "no message"}$where - this is a bug, not your script"
    }

    private fun setCwd(session: ShellSession, newCwd: String) {
        if (newCwd != session.cwd) session.vars["OLDPWD"] = session.cwd
        session.cwd = newCwd
        io.cwdChanged(newCwd)
    }

    /**
     * Reports an error (unless blank, which means "silent failure") and returns the exit status for its code
     * (see [ErrorCodes]). Every printed error carries its stable code: `line 4: [E_NOT_FOUND] Not found: ...`.
     */
    private fun fail(run: Run, line: Int, message: String, code: String? = null): Int {
        if (message.isBlank()) return 1
        run.failed++
        val c = code ?: ErrorCodes.classify(message)
        val body = if (message.startsWith("[E_")) message else "${ErrorCodes.tag(c)} $message"
        val text = if (run.multi) {
            val loc = run.session.scriptName?.let { "$it:$line" } ?: "line $line"
            "$loc: $body"
        } else body
        run.lastMessage = text
        io.print(listOf(text), LineKind.ERROR)
        return ErrorCodes.statusFor(c)
    }

    private fun quoteForTrace(s: String) =
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + s.replace("\"", "\\\"") + "\"" else s

    companion object {
        const val MAX_DEPTH = 8
        const val MAX_STEPS = 20_000
        const val MAX_REPORTS = 2_000
    }
}
