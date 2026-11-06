package com.zipcli.ke.domain.commands

import com.zipcli.ke.domain.CommandResult
import com.zipcli.ke.domain.CommandSpec
import com.zipcli.ke.domain.FlagSpec
import com.zipcli.ke.fs.VirtualPath

/** Commands that exist mainly for scripts. They are ordinary commands, so they also work at the prompt. */
fun buildScriptCommands(): List<CommandSpec> = listOf(
    echoSpec, setSpec, unsetSpec, runSpec, checkSpec, exitSpec, testSpec, trueSpec, falseSpec,
)

private val NAME_RE = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val RESERVED = setOf("PWD", "CWD")

private val echoSpec = CommandSpec(
    name = "echo",
    summary = "Print text (use -- before text that starts with -)",
    usage = "echo [text...]",
    execute = { ctx -> CommandResult.Success(listOf(ctx.parsed.args.joinToString(" "))) },
)

private val setSpec = CommandSpec(
    name = "set",
    summary = "Set variables or shell options (-e/+e stop on error, -x/+x trace, -y/+y auto-confirm, --reset)",
    usage = "set [NAME=value ...] [-e|+e] [-x|+x] [-y|+y] [--reset]",
    flags = listOf(
        FlagSpec("yes", 'y', false, "answer yes to every confirmation prompt for the rest of the session. +y turns it off"),
        FlagSpec("reset", null, false, "forget every variable and restore the defaults (errexit on, trace off, auto-yes off)"),
        FlagSpec("errexit", 'e', false, "stop at the first failing command (default). +e turns it off"),
        FlagSpec("trace", 'x', false, "print each command before running it. +x turns it off"),
    ),
    execute = execute@ { ctx ->
        val s = ctx.session
        val args = ctx.parsed.args
        var changed = false

        if (ctx.parsed.hasFlag("reset")) {
            s.vars.clear(); s.errexit = true; s.trace = false; s.autoYes = false; changed = true
        }
        if (ctx.parsed.hasFlag("yes")) { s.autoYes = true; changed = true }
        if (ctx.parsed.hasFlag("errexit")) { s.errexit = true; changed = true }
        if (ctx.parsed.hasFlag("trace")) { s.trace = true; changed = true }

        val assignments = ArrayList<Pair<String, String>>()
        val rest = ArrayList<String>()
        for (a in args) {
            if (a.length >= 2 && a[0] == '+' && a.drop(1).all { it == 'e' || it == 'x' || it == 'y' }) {
                if ('e' in a) s.errexit = false
                if ('x' in a) s.trace = false
                if ('y' in a) s.autoYes = false
                changed = true
            } else rest += a
        }
        // "set NAME value" is accepted as well as "set NAME=value"
        if (rest.size == 2 && !rest[0].contains('=') && NAME_RE.matches(rest[0])) {
            assignments += rest[0] to rest[1]
        } else {
            for (a in rest) {
                val eq = a.indexOf('=')
                if (eq <= 0) return@execute CommandResult.Failure("Usage: set NAME=value   (got '$a')")
                assignments += a.substring(0, eq) to a.substring(eq + 1)
            }
        }
        for ((k, v) in assignments) {
            if (!NAME_RE.matches(k)) return@execute CommandResult.Failure("Invalid variable name: $k")
            if (k in RESERVED) return@execute CommandResult.Failure("$k is read-only")
            s.vars[k] = v
            changed = true
        }

        if (!changed) {
            val lines = ArrayList<String>()
            lines += "errexit=${if (s.errexit) "on" else "off"}  trace=${if (s.trace) "on" else "off"}  auto-yes=${if (s.autoYes) "on" else "off"}"
            if (s.vars.isEmpty()) lines += "(no variables)"
            s.vars.toSortedMap().forEach { (k, v) -> lines += "$k=$v" }
            return@execute CommandResult.Success(lines)
        }
        CommandResult.Success()
    },
)

private val unsetSpec = CommandSpec(
    name = "unset",
    summary = "Remove variables",
    usage = "unset <NAME...>",
    minArgs = 1,
    execute = { ctx ->
        ctx.parsed.args.forEach { ctx.session.vars.remove(it) }
        CommandResult.Success()
    },
)

private val runSpec = CommandSpec(
    name = "run",
    aliases = listOf("source"),
    summary = "Run a script file (own sub-session; arguments become \$1, \$2 …)",
    usage = "run [-y] <script> [-- args...]",
    minArgs = 1,
    flags = listOf(
        FlagSpec("yes", 'y', false, "answer yes to confirmation prompts inside the script"),
    ),
    exitStatus = "0 = the script succeeded, 1 = it failed (the errors are already reported)",
    execute = execute@ { ctx ->
        val launcher = ctx.launcher
            ?: return@execute CommandResult.Failure("Scripting is not available here")
        var path = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        if (!ctx.fs.exists(path) && ctx.fs.exists("$path.zcli")) path = "$path.zcli"
        val entry = ctx.fs.stat(path)
            ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, path))
        if (entry.isDirectory) return@execute CommandResult.Failure("$path is a directory")
        val status = launcher.runFile(ctx.session, path, ctx.parsed.args.drop(1), ctx.parsed.hasFlag("yes"))
        // The script already reported its own errors, so a failure here is silent.
        if (status == 0) CommandResult.Success() else CommandResult.Failure("")
    },
)

private val checkSpec = CommandSpec(
    name = "check",
    summary = "Validate a script file without running it (quoting, heredocs, commands, flags, argument counts)",
    usage = "check <script>",
    minArgs = 1,
    maxArgs = 1,
    exitStatus = "0 = no problems found, 2 = at least one problem (all are listed, with line numbers)",
    execute = execute@ { ctx ->
        val launcher = ctx.launcher ?: return@execute CommandResult.Failure("Scripting is not available here")
        var path = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        if (!ctx.fs.exists(path) && ctx.fs.exists("$path.zcli")) path = "$path.zcli"
        val entry = ctx.fs.stat(path) ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, path))
        if (entry.isDirectory) return@execute CommandResult.Failure("$path is a directory")
        val text = ctx.fs.readText(path).removePrefix("\uFEFF")
        val report = launcher.check(text)
        if (report.problems.isEmpty()) {
            CommandResult.Success(listOf("check: OK, ${report.commands} command(s) in ${VirtualPath.name(path)}, nothing was run"))
        } else {
            val lines = report.problems + "check: ${report.problems.count { it.startsWith("line ") || it.startsWith("[E_") }.coerceAtLeast(1)} problem(s) in ${VirtualPath.name(path)}, nothing was run"
            CommandResult.Success(lines, status = 2)
        }
    },
)

private val exitSpec = CommandSpec(
    name = "exit",
    summary = "Stop the running script (optional status code)",
    usage = "exit [code]",
    maxArgs = 1,
    execute = execute@ { ctx ->
        val code = ctx.parsed.arg(0)?.let { it.toIntOrNull() ?: return@execute CommandResult.Failure("exit: '$it' is not a number") } ?: 0
        CommandResult.Exit(code)
    },
)

private val trueSpec = CommandSpec(
    name = "true", summary = "Do nothing, successfully", usage = "true", maxArgs = 0,
    execute = { CommandResult.Success() },
)

private val falseSpec = CommandSpec(
    name = "false", summary = "Do nothing, unsuccessfully", usage = "false", maxArgs = 0,
    execute = { CommandResult.Failure("") },
)

private val testSpec = CommandSpec(
    name = "test",
    summary = "Check a condition for && and || (silent; success = true)",
    usage = "test [!] -e|-f|-d <path>  |  test [!] -n|-z <text>  |  test [!] <a> = <b>  |  != ",
    flags = listOf(
        FlagSpec("exists", 'e', false, "path exists"),
        FlagSpec("file", 'f', false, "path is a file"),
        FlagSpec("dir", 'd', false, "path is a directory"),
        FlagSpec("nonempty", 'n', false, "text is not empty"),
        FlagSpec("empty", 'z', false, "text is empty"),
    ),
    exitStatus = "0 = condition true, 1 = false or bad usage (both silent, for && and ||)",
    execute = execute@ { ctx ->
        var args = ctx.parsed.args
        val negate = args.firstOrNull() == "!"
        if (negate) args = args.drop(1)
        val p = ctx.parsed

        val result: Boolean = when {
            p.hasFlag("exists") || p.hasFlag("file") || p.hasFlag("dir") -> {
                val raw = args.singleOrNull() ?: return@execute CommandResult.Failure("test: expected one path")
                val e = ctx.fs.stat(VirtualPath.resolve(ctx.cwd, raw))
                when {
                    p.hasFlag("file") -> e != null && !e.isDirectory
                    p.hasFlag("dir") -> e != null && e.isDirectory
                    else -> e != null
                }
            }
            p.hasFlag("nonempty") || p.hasFlag("empty") -> {
                val text = if (args.isEmpty()) "" else args.singleOrNull()
                    ?: return@execute CommandResult.Failure("test: expected one text argument")
                if (p.hasFlag("nonempty")) text.isNotEmpty() else text.isEmpty()
            }
            args.size == 3 && (args[1] == "=" || args[1] == "==") -> args[0] == args[2]
            args.size == 3 && args[1] == "!=" -> args[0] != args[2]
            args.size == 1 -> args[0].isNotEmpty()
            else -> return@execute CommandResult.Failure("Usage: ${ctx.parsed.name} -e <path> | <a> = <b> | -n <text>")
        }
        if (result != negate) CommandResult.Success() else CommandResult.Failure("")
    },
)
