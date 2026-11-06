package com.zipcli.ke.domain.commands

import com.zipcli.ke.domain.CommandRegistry
import com.zipcli.ke.domain.CommandResult
import com.zipcli.ke.domain.CommandSpec
import com.zipcli.ke.domain.ErrorCodes
import com.zipcli.ke.domain.FlagSpec
import com.zipcli.ke.domain.Glob
import com.zipcli.ke.domain.NaturalOrder
import com.zipcli.ke.domain.Suggest
import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.CancellationException

/**
 * Core command set:
 *   help, pwd, cd, list/ls, create, delete/rm, copy/cp, move/mv, rename, search.
 *
 * @param registryProvider late-bound accessor for the CommandRegistry (see TerminalViewModel).
 *        Needed only by `help`, which is registered alongside the commands it documents.
 */
fun buildCoreCommands(registryProvider: () -> CommandRegistry): List<CommandSpec> = listOf(
    helpSpec(registryProvider),
    infoSpec(registryProvider),
    pwdSpec,
    cdSpec,
    listSpec,
    createSpec,
    deleteSpec,
    copySpec,
    moveSpec,
    renameSpec,
    searchSpec,
)

// ───────────────────────────── help ─────────────────────────────

internal val SCRIPTING_HELP: List<String> = """
Scripting — run many commands at once
  QUOTING (read this first):
    '...'    LITERAL. No @D@ expansion; a backslash is just a backslash:  'C:\new\table.txt' is exactly that.
             The only special thing is \'  (a quote):  'it\'s'   (the POSIX form 'it'\''s' works too).
    "..."    expands @D@NAME. Escapes: \\  \"  \n  \t   and \@D@ gives a dollar before a variable name.
    heredoc  byte-exact multi-line text: no escapes, no expansion (use it for Windows paths, JSON, code):
               update /home/a.json <<'EOF'
               {"path": "C:\new\t.txt"}
               EOF
  NOT SUPPORTED (by design): pipes (|), redirection (> >> <), command substitution, arithmetic, background (&).
    Instead: separate lines for a pipe; 'update --append' or a heredoc for >; 'set NAME=value' for substitution;
    'for' for xargs. A pasted prompt such as '@D@ ls' must be pasted without the prompt.
  Paste several lines, or chain commands on one line:
    cmd1; cmd2            run both, in order
    cmd1 && cmd2          run cmd2 only if cmd1 succeeded
    cmd1 || cmd2          run cmd2 only if cmd1 failed
    # comment             ignored. End a line with \ to continue it.
  Variables:
    set NAME=value        then use @D@NAME or @D@{NAME}; @D@{NAME:-fallback} gives a default; 'set --reset' clears them all
    @D@?  last status   @D@PWD  current dir   @D@1..@D@9  script arguments
    Undefined variables are left exactly as typed. Single quotes never expand; double quotes do.
  Loops and wildcards:
    for f in *.txt; do copy @D@f /home/backup; done
    zip, copy, move, delete, cat, list and for expand * ? and ** (zip all.zip **/*.log).
    find, search and replace take a pattern instead: find uses --name '<glob>' with -r; search takes the glob itself.
  Script files:
    run backup.zcli [args]   run a file in its own sub-session (-y answers prompts with yes)
    check backup.zcli        validate a script without running it (every problem at once, with line numbers)
    Scripts stop at the first error. 'set +e' keeps going, 'set -x' traces each command.
  Confirmations: delete asks first. Add --yes (or -y) to that command, or 'set -y' for the whole session.
    Over MCP a prompt stops the batch BEFORE the prompt and reports how many commands did not run.
  Errors look like  line 4: [E_NOT_FOUND] Not found: ...   Codes and exit statuses: 'help exit-codes'.
  Helpers: echo, test -e|-f|-d <path>, test a = b, true, false, exit [n], info, check
""".trimIndent().replace("@D@", "$").lines()

internal val EXIT_CODES_HELP = listOf(
    "Exit statuses (also shown as [exit N] over MCP, and in \$?)",
    "  0  success",
    "  1  no match (find, search, replace --fail-on-no-match), a false test, or another failure",
    "  2  usage error: unknown command, unknown flag, wrong number of arguments, syntax error",
    "  3  path not found",
    "  4  needs confirmation (the batch stopped before the prompt; nothing after it ran)",
    "  5  partial batch: some commands did not run",
    "Error codes (printed as [E_...] before the message):",
    "  E_USAGE  E_NOT_FOUND  E_IS_DIR  E_NEEDS_CONFIRM  E_INTERNAL (a bug in ZipCLI, not your script)  E_FAILED (anything else)",
)

private fun flagJson(f: FlagSpec) = Json.Raw(Json.obj(
    "long" to f.long, "short" to f.short?.toString(), "takes_value" to f.takesValue, "description" to f.description,
))

private fun specJson(spec: CommandSpec) = Json.Raw(Json.obj(
    "name" to spec.name, "aliases" to spec.aliases, "summary" to spec.summary, "usage" to spec.usage,
    "min_args" to spec.minArgs, "max_args" to (if (spec.maxArgs == Int.MAX_VALUE) null else spec.maxArgs),
    "expands_wildcards" to spec.expandGlobs, "may_confirm" to spec.mayConfirm,
    "exit_status" to spec.exitStatus, "flags" to spec.flags.map { flagJson(it) },
))

private fun helpSpec(registryProvider: () -> CommandRegistry) = CommandSpec(
    name = "help",
    aliases = listOf("?"),
    summary = "Show commands or help for one command (--json for the flag schema)",
    usage = "help [command | scripting | exit-codes] [--json]",
    maxArgs = 1,
    flags = listOf(
        FlagSpec("json", null, false, "machine-readable: the command list or one command's flags as JSON"),
        FlagSpec("machine", null, false, "same as --json"),
    ),
    execute = execute@ { ctx ->
        val target = ctx.parsed.arg(0)
        val json = ctx.parsed.hasFlag("json") || ctx.parsed.hasFlag("machine")
        if (target == null) {
            if (json) return@execute CommandResult.Success(listOf(Json.arr(registryProvider().all().map { specJson(it) })))
            val lines = buildList {
                add("Available commands:")
                registryProvider().all().forEach { spec ->
                    add("  ${spec.name.padEnd(10)} ${spec.summary}")
                }
                add("")
                add("Tip: 'help <command>' for flags, 'help scripting' for quoting and batches, 'help exit-codes' for statuses.")
            }
            return@execute CommandResult.Success(lines)
        }
        if (target.lowercase().let { it == "scripting" || it == "script" || it == "scripts" || it == "quoting" }) {
            return@execute CommandResult.Success(SCRIPTING_HELP)
        }
        if (target.lowercase().let { it == "exit-codes" || it == "codes" || it == "errors" }) {
            return@execute CommandResult.Success(EXIT_CODES_HELP)
        }

        val spec = registryProvider().find(target)
            ?: return@execute CommandResult.Failure(
                "Unknown command: $target" +
                    (Suggest.closest(target, registryProvider().names())?.let { ". Did you mean '$it'?" } ?: "")
            )
        if (json) return@execute CommandResult.Success(listOf(specJson(spec).json))

        val lines = buildList {
            add(spec.name + if (spec.aliases.isEmpty()) "" else " (${spec.aliases.joinToString()})")
            add("  ${spec.summary}")
            add("  Usage: ${spec.usage}")
            if (spec.expandGlobs) add("  Wildcards (* ? **) in arguments are expanded.")
            if (spec.mayConfirm) add("  Asks for confirmation; add --yes (or -y) to authorise it in a script.")
            spec.exitStatus?.let { add("  Exit status: $it") }
            add("  All statuses and error codes: help exit-codes")
            if (spec.flags.isNotEmpty()) {
                add("  Flags:")
                spec.flags.forEach { f ->
                    val flag = (if (f.short != null) "-${f.short}, " else "    ") +
                            "--${f.long}" + if (f.takesValue) " <value>" else ""
                    add("    ${flag.padEnd(24)} ${f.description}")
                }
            }
        }
        CommandResult.Success(lines)
    },
)

// ───────────────────────────── info ─────────────────────────────

private fun infoSpec(registryProvider: () -> CommandRegistry) = CommandSpec(
    name = "info",
    summary = "Capability report: version, mounts, available commands, limits, shell state",
    usage = "info [--json]",
    maxArgs = 0,
    flags = listOf(FlagSpec("json", null, false, "machine-readable report")),
    execute = execute@ { ctx ->
        val reg = registryProvider()
        val s = ctx.session
        val mounts = try { ctx.fs.list("/").map { "/" + it.name } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val have = reg.all().map { it.name }
        val unavailable = com.zipcli.ke.domain.CommandParser.APP_ONLY_COMMANDS.filter { it !in have }
        val limits = linkedMapOf(
            "cat_lines" to Limits.CAT_LINES, "cat_lines_max" to Limits.CAT_LINES_MAX,
            "find_matches" to Limits.FIND_MATCHES, "find_matches_max" to Limits.FIND_MATCHES_MAX,
            "mcp_output_chars" to Limits.MCP_OUTPUT_CHARS, "mcp_command_chars" to Limits.MCP_COMMAND_CHARS,
            "script_commands" to Limits.SCRIPT_COMMANDS,
        )
        if (ctx.parsed.hasFlag("json")) {
            val body = Json.obj(
                "version" to Limits.APP_VERSION, "transport" to ctx.transport, "cwd" to ctx.cwd, "mounts" to mounts,
                "commands" to have, "unavailable_here" to unavailable,
                "errexit" to s.errexit, "trace" to s.trace, "auto_yes" to s.autoYes,
                "variables" to Json.Raw(Json.obj(*s.vars.toSortedMap().map { (k, v) -> k to v as Any? }.toTypedArray())),
                "limits" to Json.Raw(Json.obj(*limits.map { (k, v) -> k to v as Any? }.toTypedArray())),
                "exit_codes" to Json.Raw(Json.obj(
                    "ok" to 0, "no_match_or_failed" to 1, "usage" to 2, "not_found" to 3, "needs_confirmation" to 4, "partial" to 5)),
                "error_codes" to ErrorCodes.ALL,
            )
            return@execute CommandResult.Success(listOf(body))
        }
        val lines = ArrayList<String>()
        lines += "ZipCLI ${Limits.APP_VERSION}  transport: ${ctx.transport}"
        lines += "cwd: ${ctx.cwd}"
        lines += "mounts: " + (if (mounts.isEmpty()) "(unknown)" else mounts.joinToString(", "))
        lines += "commands: ${have.joinToString(" ")}"
        if (unavailable.isNotEmpty()) lines += "not available in this session: ${unavailable.joinToString(", ")} (app-only: interactive or manage the server)"
        lines += "shell: errexit=${if (s.errexit) "on" else "off"} trace=${if (s.trace) "on" else "off"} auto-yes=${if (s.autoYes) "on" else "off"} variables=${s.vars.size}"
        lines += "limits: cat ${Limits.CAT_LINES} lines (--lines, max ${Limits.CAT_LINES_MAX}); find ${Limits.FIND_MATCHES} matches (--max, max ${Limits.FIND_MATCHES_MAX}); " +
            "MCP output ${Limits.MCP_OUTPUT_CHARS} characters; MCP command ${Limits.MCP_COMMAND_CHARS} characters; ${Limits.SCRIPT_COMMANDS} commands per script"
        lines += "no pipes, no redirection, no command substitution, no arithmetic. See: help scripting, help exit-codes"
        CommandResult.Success(lines)
    },
)

// ───────────────────────────── pwd ─────────────────────────────

private val pwdSpec = CommandSpec(
    name = "pwd",
    summary = "Print current directory",
    usage = "pwd",
    maxArgs = 0,
    execute = { ctx -> CommandResult.Success(listOf(ctx.cwd)) },
)

// ───────────────────────────── cd ─────────────────────────────

private val cdSpec = CommandSpec(
    name = "cd",
    summary = "Change directory (no argument: /home, '-': previous)",
    usage = "cd [path | -]",
    maxArgs = 1,
    execute = execute@ { ctx ->
        val arg = ctx.parsed.arg(0)
        val back = arg == "-"
        val target = when {
            arg == null -> "/home"
            back -> ctx.session.vars["OLDPWD"]
                ?: return@execute CommandResult.Failure("No previous directory")
            else -> VirtualPath.resolve(ctx.cwd, arg)
        }
        val entry = ctx.fs.stat(target)
            ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, target, dirsOnly = true).replace("Not found", "No such directory"))
        if (!entry.isDirectory) {
            return@execute CommandResult.Failure("Not a directory: $target")
        }
        CommandResult.Success(if (back) listOf(target) else emptyList(), newCwd = target)
    },
)

// ───────────────────────────── list / ls ─────────────────────────────

private val listSpec = CommandSpec(
    name = "list",
    aliases = listOf("ls"),
    summary = "List directory contents (folders first); wildcards like *.md are expanded",
    usage = "list [path...] [-l] [-a] [--json]",
    expandGlobs = true,
    flags = listOf(
        FlagSpec("long", 'l', false, "long format with size and date"),
        FlagSpec("all", 'a', false, "include hidden entries"),
        FlagSpec("json", null, false, "machine-readable: [{path, entries:[{name,type,size,mtime}]}]"),
    ),
    execute = execute@ { ctx ->
        val targets = if (ctx.parsed.args.isEmpty()) listOf(".") else ctx.parsed.args
        val json = ctx.parsed.hasFlag("json")
        val long = ctx.parsed.hasFlag("long")
        val out = ArrayList<String>()
        val blocks = ArrayList<Json.Raw>()
        for ((idx, arg) in targets.withIndex()) {
            val path = VirtualPath.resolve(ctx.cwd, arg)
            val root = ctx.fs.stat(path)
                ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, path))
            val entries: List<FileEntry> = if (root.isDirectory) {
                ctx.fs.list(path).filter { ctx.parsed.hasFlag("all") || !it.name.startsWith(".") }
            } else listOf(root)
            val sorted = entries.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            )
            if (json) {
                blocks += Json.Raw(Json.obj("path" to path, "entries" to sorted.map { e ->
                    Json.Raw(Json.obj("name" to e.name, "type" to (if (e.isDirectory) "dir" else "file"),
                        "size" to (if (e.isDirectory) null else e.size), "mtime" to e.lastModified))
                }))
                continue
            }
            if (targets.size > 1 && root.isDirectory) { if (idx > 0 && out.isNotEmpty()) out += ""; out += "$path:" }
            when {
                sorted.isEmpty() -> out += "(empty)"
                long -> sorted.forEach { e ->
                    val type = if (e.isDirectory) "d" else "-"
                    val size = if (e.isDirectory) "-" else formatSize(e.size)
                    out += "%s %8s  %s  %s".format(type, size, formatDate(e.lastModified), e.name + if (e.isDirectory) "/" else "")
                }
                else -> sorted.forEach { e -> out += if (e.isDirectory) "${e.name}/" else e.name }
            }
        }
        if (json) CommandResult.Success(listOf(Json.arr(blocks))) else CommandResult.Success(out)
    },
)

// ───────────────────────────── create ─────────────────────────────

private val createSpec = CommandSpec(
    name = "create",
    summary = "Create files or directories (a trailing / means directory)",
    usage = "create <path...> [--dir] [--content <text> | <<'EOF' heredoc]",
    minArgs = 1,
    flags = listOf(
        FlagSpec("dir", 'd', false, "create directories"),
        FlagSpec("content", null, true, "initial file content. '...' is literal (only \\' is special); for byte-exact text such as Windows paths or JSON use a heredoc: create f <<'EOF' ... EOF"),
    ),
    execute = execute@ { ctx ->
        val wantDir = ctx.parsed.hasFlag("dir")
        val targets = ctx.parsed.args.map { raw ->
            Triple(raw, VirtualPath.resolve(ctx.cwd, raw), wantDir || raw.endsWith("/"))
        }
        for ((_, path, _) in targets) {
            if (ctx.fs.exists(path)) return@execute CommandResult.Failure("Already exists: $path")
        }
        val content = ctx.parsed.flagValue("content")
        val out = ArrayList<String>()
        for ((_, path, isDir) in targets) {
            if (isDir) {
                ctx.fs.mkdirs(path)
                out += "Created directory $path"
            } else {
                if (content != null) ctx.fs.writeText(path, content) else ctx.fs.createFile(path)
                out += "Created file $path"
            }
        }
        CommandResult.Success(out)
    },
)

// ───────────────────────────── delete / rm ─────────────────────────────

private val deleteSpec = CommandSpec(
    name = "delete",
    aliases = listOf("rm"),
    summary = "Delete files or directories (asks first, shows what is inside)",
    usage = "delete <path...> [-r] [-f]",
    minArgs = 1,
    expandGlobs = true,
    mayConfirm = true,
    exitStatus = "0 = deleted (or already gone with -f), 1 = error, 3 = path not found, 4 = needs confirmation (stopped before the prompt over MCP)",
    flags = listOf(
        FlagSpec("recursive", 'r', false, "delete directories recursively"),
        FlagSpec("force", 'f', false, "skip confirmation and paths that do not exist (--yes / -y skips only the confirmation)"),
    ),
    execute = execute@ { ctx ->
        val recursive = ctx.parsed.hasFlag("recursive")
        val force = ctx.parsed.hasFlag("force")
        val paths = ctx.parsed.args.map { VirtualPath.resolve(ctx.cwd, it) }.distinct()
        val entries = ArrayList<FileEntry>()
        val missing = ArrayList<String>()
        for (p in paths) {
            if (isProtectedRoot(p)) {
                return@execute CommandResult.Failure("Refusing to delete $p: it is a storage root")
            }
            val entry = ctx.fs.stat(p)
            if (entry == null) {
                // Like 'rm -f': a path that is already gone is not an error, so a script can
                // clear a previous run without an existence guard.
                if (force) { missing += p; continue }
                return@execute CommandResult.Failure(notFoundMessage(ctx.fs, p))
            }
            if (entry.isDirectory && !recursive) {
                return@execute CommandResult.Failure("$p is a directory; use -r")
            }
            entries += entry
        }
        if (entries.isEmpty()) {
            return@execute CommandResult.Success(listOf("Nothing to delete: ${missing.joinToString()} (already gone)"))
        }

        suspend fun doDelete(): CommandResult {
            val errors = ArrayList<String>()
            var done = 0
            for (e in entries) {
                try { ctx.fs.delete(e.path, recursive); done++ }
                catch (ex: CancellationException) { throw ex }
                catch (ex: Exception) { errors += "${e.path}: ${ex.message ?: ex.javaClass.simpleName}" }
            }
            return when {
                errors.isNotEmpty() -> CommandResult.Failure(
                    (if (done > 0) "Deleted $done, but failed on:\n" else "Delete failed:\n") + errors.joinToString("\n"))
                entries.size == 1 -> CommandResult.Success(listOf("Deleted ${entries[0].path}"))
                else -> CommandResult.Success(listOf("Deleted $done items"))
            }
        }

        if (ctx.parsed.hasFlag("force")) return@execute doDelete()

        // Tell the user exactly what they are about to lose.
        var files = 0; var dirs = 0; var bytes = 0L
        for (e in entries) {
            if (e.isDirectory) {
                ctx.progress("Counting ${e.path}…")
                val s = treeStats(ctx.fs, e.path)
                dirs += 1 + s.dirs; files += s.files; bytes += s.bytes
            } else { files++; bytes += e.size }
        }
        val what = when {
            entries.size == 1 && !entries[0].isDirectory -> "file ${entries[0].path} (${formatSize(bytes)})"
            entries.size == 1 -> "directory ${entries[0].path} ($files files, $dirs folders, ${formatSize(bytes)})"
            else -> "${entries.size} items ($files files, $dirs folders, ${formatSize(bytes)})"
        }
        CommandResult.Confirmation("Delete $what? [y/N]") { doDelete() }
    },
)

// ───────────────────────────── copy / cp ─────────────────────────────

private val copySpec = CommandSpec(
    name = "copy",
    aliases = listOf("cp"),
    summary = "Copy files or directories (into a directory if the target is one)",
    usage = "copy <src...> <dst> [-r]",
    minArgs = 2,
    expandGlobs = true,
    flags = listOf(
        FlagSpec("recursive", 'r', false, "accepted for convenience; directories are always copied recursively"),
    ),
    execute = execute@ { ctx ->
        val srcs = ctx.parsed.args.dropLast(1).map { VirtualPath.resolve(ctx.cwd, it) }
        val dst = VirtualPath.resolve(ctx.cwd, ctx.parsed.args.last())
        val dstEntry = ctx.fs.stat(dst)
        if (srcs.size > 1 && dstEntry?.isDirectory != true) {
            return@execute CommandResult.Failure("With several sources the target must be an existing directory: $dst")
        }

        class Job(val src: String, val target: String, val replaces: Boolean)
        val jobs = ArrayList<Job>()
        for (src in srcs) {
            val entry = ctx.fs.stat(src) ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, src))
            val target = resolveTarget(ctx.fs, src, dst)
            if (VirtualPath.normalize(src) == target) {
                return@execute CommandResult.Failure("'$src' and '$target' are the same")
            }
            if (entry.isDirectory && isInside(src, target)) {
                return@execute CommandResult.Failure("Cannot copy $src into itself ($target)")
            }
            val existing = ctx.fs.stat(target)
            if (existing != null && existing.isDirectory != entry.isDirectory) {
                return@execute CommandResult.Failure("Cannot overwrite $target: one is a file, the other a directory")
            }
            jobs += Job(src, target, existing != null && !entry.isDirectory)
        }

        for ((i, j) in jobs.withIndex()) {
            ctx.progress("Copying ${j.src} (${i + 1}/${jobs.size})")
            ctx.fs.copy(j.src, j.target, recursive = true)
        }
        if (jobs.size == 1) {
            val j = jobs[0]
            CommandResult.Success(listOf("Copied ${j.src} -> ${j.target}" + if (j.replaces) " (replaced existing file)" else ""))
        } else {
            val replaced = jobs.count { it.replaces }
            CommandResult.Success(listOf("Copied ${jobs.size} items to $dst" + if (replaced > 0) " ($replaced replaced)" else ""))
        }
    },
)

// ───────────────────────────── move / mv ─────────────────────────────

private val moveSpec = CommandSpec(
    name = "move",
    aliases = listOf("mv"),
    summary = "Move or rename (into a directory if the target is one)",
    usage = "move <src...> <dst>",
    minArgs = 2,
    expandGlobs = true,
    execute = execute@ { ctx ->
        val srcs = ctx.parsed.args.dropLast(1).map { VirtualPath.resolve(ctx.cwd, it) }
        val dst = VirtualPath.resolve(ctx.cwd, ctx.parsed.args.last())
        val dstEntry = ctx.fs.stat(dst)
        if (srcs.size > 1 && dstEntry?.isDirectory != true) {
            return@execute CommandResult.Failure("With several sources the target must be an existing directory: $dst")
        }

        val pairs = ArrayList<Pair<String, String>>()
        for (src in srcs) {
            val entry = ctx.fs.stat(src) ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, src))
            if (isProtectedRoot(src)) return@execute CommandResult.Failure("Refusing to move $src: it is a storage root")
            val target = resolveTarget(ctx.fs, src, dst)
            if (VirtualPath.normalize(src) == target) {
                return@execute CommandResult.Failure("'$src' and '$target' are the same")
            }
            if (entry.isDirectory && isInside(src, target)) {
                return@execute CommandResult.Failure("Cannot move $src into itself ($target)")
            }
            if (ctx.fs.exists(target)) return@execute CommandResult.Failure("Target exists: $target")
            if (pairs.any { it.second == target }) return@execute CommandResult.Failure("Two sources would both become $target")
            pairs += src to target
        }

        for ((i, p) in pairs.withIndex()) {
            ctx.progress("Moving ${p.first} (${i + 1}/${pairs.size})")
            ctx.fs.move(p.first, p.second)
        }
        if (pairs.size == 1) CommandResult.Success(listOf("Moved ${pairs[0].first} -> ${pairs[0].second}"))
        else CommandResult.Success(listOf("Moved ${pairs.size} items to $dst"))
    },
)

// ───────────────────────────── rename ─────────────────────────────

private val renameSpec = CommandSpec(
    name = "rename",
    summary = "Rename within the same directory",
    usage = "rename <src> <newName>",
    minArgs = 2,
    maxArgs = 2,
    execute = execute@ { ctx ->
        val src = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        val newName = ctx.parsed.arg(1)!!
        if (newName.isEmpty() || newName.contains('/')) {
            return@execute CommandResult.Failure("rename takes a bare name; use move for paths")
        }
        if (!ctx.fs.exists(src)) return@execute CommandResult.Failure(notFoundMessage(ctx.fs, src))
        if (isProtectedRoot(src)) return@execute CommandResult.Failure("Refusing to rename $src: it is a storage root")
        val parent = src.substringBeforeLast('/', missingDelimiterValue = "")
        val dst = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
        if (ctx.fs.exists(dst)) return@execute CommandResult.Failure("Target exists: $dst")
        ctx.fs.move(src, dst)
        CommandResult.Success(listOf("Renamed $src -> $dst"))
    },
)

// ───────────────────────────── search (filenames) ─────────────────────────────

private val searchSpec = CommandSpec(
    name = "search",
    summary = "Find files by name (plain text = contains; or glob * ?)",
    usage = "search <pattern> [path] [-r] [-s] [--max N] [--offset N] [--count-only] [--json]",
    minArgs = 1,
    maxArgs = 2,
    flags = listOf(
        FlagSpec("recursive", 'r', false, "search recursively"),
        FlagSpec("case", 's', false, "case-sensitive (default: ignore case unless the pattern has capitals)"),
        FlagSpec("max", null, true, "maximum results to print (default ${Limits.SEARCH_RESULTS})"),
        FlagSpec("offset", null, true, "skip the first N results (continue a capped list)"),
        FlagSpec("count-only", null, false, "print only how many files match"),
        FlagSpec("json", null, false, "machine-readable: {results:[{path,type,size,mtime}], count, truncated, next_offset}"),
    ),
    exitStatus = "0 = at least one match, 1 = no matches (grep-style), 2 = usage error, 3 = path not found",
    execute = execute@ { ctx ->
        val pattern = ctx.parsed.arg(0)!!
        val root = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(1) ?: ".")
        val recursive = ctx.parsed.hasFlag("recursive")
        val json = ctx.parsed.hasFlag("json")
        val countOnly = ctx.parsed.hasFlag("count-only")
        val max = ctx.parsed.flagValue("max")?.toIntOrNull()?.coerceIn(1, 100_000) ?: Limits.SEARCH_RESULTS
        val offset = ctx.parsed.flagValue("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        // Smart matching: "report" means "*report*"; case is ignored unless the pattern has capitals.
        val glob = if (Glob.hasWildcard(pattern)) pattern else "*$pattern*"
        val ignoreCase = !ctx.parsed.hasFlag("case") && pattern.none { it.isUpperCase() }
        val regex = Glob.toRegex(glob, ignoreCase)

        if (ctx.fs.stat(root) == null) return@execute CommandResult.Failure(notFoundMessage(ctx.fs, root,
            wildcardHint = "'search' does not expand wildcards in <path>: pass a folder, and put the glob in <pattern> (search '*.md' /home -r)."))

        val entries = if (recursive) {
            ctx.fs.walk(root).filter { it.path != root }
        } else {
            ctx.fs.list(root)
        }

        val matches = entries
            .filter { regex.matches(it.name) }
            .sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }

        if (matches.isEmpty()) {
            // Same convention as find/grep: nothing found is status 1.
            val text = when {
                json -> Json.obj("results" to emptyList<Any>(), "count" to 0, "truncated" to false, "next_offset" to null)
                !recursive -> "No matches in $root. Try -r to search subfolders."
                else -> "No matches."
            }
            return@execute CommandResult.Success(listOf(text), status = 1)
        }
        val page = matches.drop(offset).take(max)
        val capped = offset + page.size < matches.size
        when {
            json -> CommandResult.Success(listOf(Json.obj(
                "results" to page.map { e -> Json.Raw(Json.obj("path" to e.path, "type" to (if (e.isDirectory) "dir" else "file"),
                    "size" to (if (e.isDirectory) null else e.size), "mtime" to e.lastModified)) },
                "count" to matches.size, "truncated" to capped, "next_offset" to (if (capped) offset + page.size else null),
            )))
            countOnly -> CommandResult.Success(listOf("${matches.size} match(es)"))
            else -> {
                val out = ArrayList<String>(page.map { if (it.isDirectory) it.path + "/" else it.path })
                if (capped) out += "[showing ${page.size} of ${matches.size} — use --max to raise, or --offset ${offset + page.size} to continue]"
                CommandResult.Success(out)
            }
        }
    },
)
