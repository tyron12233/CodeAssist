package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.Location
import dev.ide.agent.RunResult
import dev.ide.agent.TaskRunResult
import dev.ide.agent.TextEdit
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema

/**
 * The built-in tool set, bound to an [AgentWorkspace]. Read tools never mutate; write tools set
 * [AgentTool.mutating] so the loop gates them through the permission policy. Output is formatted to be
 * compact and readable to the model.
 */
fun builtinTools(ws: AgentWorkspace): List<AgentTool> = listOf(
    tool(
        name = "read_file",
        description = "Read a file's current text, including any unsaved editor changes. Optionally restrict to a 1-based, inclusive line range.",
        parameters = toolSchema {
            string("path", "File path, absolute or workspace-relative.")
            integer("start_line", "First line to read (1-based).", required = false)
            integer("end_line", "Last line to read (1-based, inclusive).", required = false)
        },
        summary = { "read ${it.optString("path") ?: "file"}" },
    ) { args -> ToolExecutionResult.ok(ws.readFile(args.string("path"), args.optInt("start_line"), args.optInt("end_line"))) },

    tool(
        name = "list_dir",
        description = "List the entries of a directory.",
        parameters = toolSchema { string("path", "Directory path.") },
        summary = { "list ${it.optString("path") ?: "dir"}" },
    ) { args ->
        val entries = ws.listDir(args.string("path"))
        if (entries.isEmpty()) ToolExecutionResult.ok("(empty)")
        else ToolExecutionResult.ok(entries.joinToString("\n") { (if (it.isDirectory) "[dir] " else "      ") + it.name })
    },

    tool(
        name = "search_text",
        description = "Search file contents across the project and return matching locations.",
        parameters = toolSchema {
            string("query", "Text or regular expression to find.")
            boolean("regex", "Treat the query as a regular expression.", required = false)
            boolean("case_sensitive", "Match case-sensitively.", required = false)
        },
        summary = { "search \"${it.optString("query").orEmpty()}\"" },
    ) { args ->
        val matches = ws.searchText(
            query = args.string("query"),
            regex = args.optBoolean("regex") ?: false,
            caseSensitive = args.optBoolean("case_sensitive") ?: false,
        )
        if (matches.isEmpty()) ToolExecutionResult.ok("No matches.")
        else ToolExecutionResult.ok(matches.joinToString("\n") { "${it.path}:${it.line}:${it.column}: ${it.lineText.trim()}" })
    },

    tool(
        name = "find_symbol",
        description = "Find declarations (classes, methods, fields) by name or fragment.",
        parameters = toolSchema { string("query", "Symbol name or fragment.") },
        summary = { "find symbol \"${it.optString("query").orEmpty()}\"" },
    ) { args ->
        val hits = ws.findSymbol(args.string("query"))
        if (hits.isEmpty()) ToolExecutionResult.ok("No symbols found.")
        else ToolExecutionResult.ok(hits.joinToString("\n") { hit ->
            "${hit.kind} ${hit.name}" + (hit.path?.let { "  $it:${hit.line}" } ?: "")
        })
    },

    tool(
        name = "get_diagnostics",
        description = "Report compiler and analyzer diagnostics for a file over its current text. Use this to check that an edit is valid.",
        parameters = toolSchema { string("path", "File path.") },
        summary = { "diagnostics ${it.optString("path") ?: "file"}" },
    ) { args ->
        val diagnostics = ws.diagnostics(args.string("path"))
        if (diagnostics.isEmpty()) ToolExecutionResult.ok("No diagnostics.")
        else ToolExecutionResult.ok(diagnostics.joinToString("\n") { "${it.line}:${it.column} ${it.severity}: ${it.message}" })
    },

    tool(
        name = "project_overview",
        description = "Summarize the project: its modules, their types, source roots, and dependencies.",
        parameters = toolSchema { },
        summary = { "project overview" },
    ) { _ ->
        val overview = ws.projectOverview()
        val sb = StringBuilder("Project: ${overview.name}")
        overview.modules.forEach { m ->
            sb.append("\n\nModule ${m.name} (${m.type})")
            m.languageLevel?.let { sb.append(", language level ").append(it) }
            sb.append("\n  source roots: ").append(m.sourceRoots.joinToString(", ").ifEmpty { "(none)" })
            sb.append("\n  dependencies: ").append(m.dependencies.joinToString(", ").ifEmpty { "(none)" })
        }
        ToolExecutionResult.ok(sb.toString())
    },

    tool(
        name = "create_file",
        description = "Create a new file with the given content, creating parent directories as needed. Fails if the file already exists.",
        parameters = toolSchema {
            string("path", "Path of the new file.")
            string("content", "Full file content.")
        },
        mutating = true,
        summary = { "create ${it.optString("path") ?: "file"}" },
    ) { args -> ToolExecutionResult.ok("Created " + ws.createFile(args.string("path"), args.string("content"))) },

    tool(
        name = "write_file",
        description = "Replace a file's entire content, creating it if it does not exist. Prefer edit_file for small changes.",
        parameters = toolSchema {
            string("path", "File path.")
            string("content", "New full content.")
        },
        mutating = true,
        summary = { "write ${it.optString("path") ?: "file"}" },
    ) { args ->
        ws.writeFile(args.string("path"), args.string("content"))
        ToolExecutionResult.ok("Wrote ${args.string("path")}")
    },

    tool(
        name = "edit_file",
        description = "Replace an exact snippet in a file. old_string must match the file exactly and occur once, unless replace_all is set.",
        parameters = toolSchema {
            string("path", "File path.")
            string("old_string", "Exact text to replace.")
            string("new_string", "Replacement text.")
            boolean("replace_all", "Replace every occurrence instead of requiring a unique match.", required = false)
        },
        mutating = true,
        summary = { "edit ${it.optString("path") ?: "file"}" },
    ) { args -> editFile(ws, args) },

    tool(
        name = "create_dir",
        description = "Create a directory, including intermediate directories.",
        parameters = toolSchema { string("path", "Directory path.") },
        mutating = true,
        summary = { "create dir ${it.optString("path") ?: ""}" },
    ) { args -> ToolExecutionResult.ok("Created " + ws.createDir(args.string("path"))) },

    tool(
        name = "rename_path",
        description = "Rename a file or directory in place.",
        parameters = toolSchema {
            string("path", "Path to rename.")
            string("new_name", "New simple name (not a full path).")
        },
        mutating = true,
        summary = { "rename ${it.optString("path") ?: ""}" },
    ) { args -> ToolExecutionResult.ok("Renamed to " + ws.renamePath(args.string("path"), args.string("new_name"))) },

    tool(
        name = "move_path",
        description = "Move a file or directory into a destination directory.",
        parameters = toolSchema {
            string("path", "Path to move.")
            string("dest_dir", "Destination directory.")
        },
        mutating = true,
        summary = { "move ${it.optString("path") ?: ""}" },
    ) { args -> ToolExecutionResult.ok("Moved to " + ws.movePath(args.string("path"), args.string("dest_dir"))) },

    tool(
        name = "delete_path",
        description = "Delete a file or directory.",
        parameters = toolSchema { string("path", "Path to delete.") },
        mutating = true,
        summary = { "delete ${it.optString("path") ?: ""}" },
    ) { args ->
        val ok = ws.deletePath(args.string("path"))
        if (ok) ToolExecutionResult.ok("Deleted ${args.string("path")}")
        else ToolExecutionResult.error("Path not found: ${args.string("path")}")
    },

    tool(
        name = "add_dependency",
        description = "Add a Maven-coordinate dependency (group:name:version) to a module.",
        parameters = toolSchema {
            string("module", "Module name.")
            string("coordinate", "Maven coordinate, for example com.squareup.okhttp3:okhttp:4.12.0.")
        },
        mutating = true,
        summary = { "add ${it.optString("coordinate").orEmpty()} to ${it.optString("module").orEmpty()}" },
    ) { args -> ToolExecutionResult.ok(ws.addDependency(args.string("module"), args.string("coordinate"))) },

    tool(
        name = "run_program",
        description = "Compile a module and run its main() on the in-process VM, returning the program output, " +
            "exit code, and any compile errors. Optionally pipe text to standard input (consumed line by line, " +
            "then EOF). Use this to verify that a change actually builds and behaves correctly. The run is " +
            "sandboxed and time-limited.",
        parameters = toolSchema {
            string("module", "Module to run. Defaults to the project's main module.", required = false)
            string("stdin", "Text piped to the program's standard input.", required = false)
        },
        // Executes code with real side effects, so it is permission-gated like the other impactful tools.
        mutating = true,
        summary = { "run ${it.optString("module") ?: "program"}" },
    ) { args -> formatRun(ws.runProgram(args.optString("module"), args.optString("stdin").orEmpty())) },

    // --- Code intelligence (uses the engine's semantic navigation, not text matching) ---

    tool(
        name = "go_to_definition",
        description = "Resolve the symbol on a line to its declaration location(s). Give the 1-based line and the " +
            "exact identifier text on that line. Supported for Kotlin sources and Android resource references " +
            "(@type/name, R.type.name).",
        parameters = toolSchema {
            string("path", "File path.")
            integer("line", "1-based line the symbol is on.")
            string("symbol", "The identifier to resolve on that line (a method, class, or resource name).", required = false)
        },
        summary = { "go to definition of ${it.optString("symbol") ?: "symbol"}" },
    ) { args ->
        val locs = ws.goToDefinition(args.string("path"), resolveOffset(ws, args.string("path"), args.int("line"), args.optString("symbol")))
        if (locs.isEmpty()) ToolExecutionResult.ok("No definition found (Kotlin sources and Android resource references are supported).")
        else ToolExecutionResult.ok(locs.joinToString("\n") { formatLocation(it) })
    },

    tool(
        name = "find_references",
        description = "Find every reference to the symbol on a line, project-wide. Give the 1-based line and the " +
            "identifier text on that line. Java (JDT) today.",
        parameters = toolSchema {
            string("path", "File path.")
            integer("line", "1-based line the symbol is on.")
            string("symbol", "The identifier to find references to.", required = false)
        },
        summary = { "find references to ${it.optString("symbol") ?: "symbol"}" },
    ) { args ->
        val refs = ws.findReferences(args.string("path"), resolveOffset(ws, args.string("path"), args.int("line"), args.optString("symbol")))
        if (refs.isEmpty()) ToolExecutionResult.ok("No references found.")
        else ToolExecutionResult.ok("${refs.size} reference(s):\n" + refs.joinToString("\n") { formatLocation(it) })
    },

    tool(
        name = "project_diagnostics",
        description = "Report compiler and analyzer diagnostics across every source file in the project. Set " +
            "errors_only to skip warnings and infos. Use this to survey the health of the whole project.",
        parameters = toolSchema { boolean("errors_only", "Report only errors.", required = false) },
        summary = { "project diagnostics" },
    ) { args ->
        val diags = ws.projectDiagnostics(args.optBoolean("errors_only") ?: false)
        if (diags.isEmpty()) ToolExecutionResult.ok("No diagnostics.")
        else ToolExecutionResult.ok(diags.joinToString("\n") { "${it.path}:${it.line}:${it.column} ${it.severity}: ${it.message}" })
    },

    tool(
        name = "rename_symbol",
        description = "Semantically rename the symbol on a line to a new name across the whole project, updating " +
            "every reference. Give the 1-based line, the current identifier on that line, and the new name. Java " +
            "(JDT) today. Prefer this over edit_file for renames.",
        parameters = toolSchema {
            string("path", "File path.")
            integer("line", "1-based line the symbol is on.")
            string("symbol", "The current identifier to rename.")
            string("new_name", "The new identifier.")
        },
        mutating = true,
        summary = { "rename ${it.optString("symbol").orEmpty()} to ${it.optString("new_name").orEmpty()}" },
    ) { args ->
        val r = ws.renameSymbol(
            args.string("path"),
            resolveOffset(ws, args.string("path"), args.int("line"), args.optString("symbol")),
            args.string("new_name"),
        )
        if (!r.success) ToolExecutionResult.error(r.message)
        else ToolExecutionResult.ok("${r.message} (${r.occurrences} occurrence(s) across ${r.filesChanged} file(s)).")
    },

    tool(
        name = "list_quick_fixes",
        description = "List the quick fixes and intentions available on a line (add a missing import, remove an " +
            "unused one, create a resource, and so on). Apply one with apply_quick_fix.",
        parameters = toolSchema {
            string("path", "File path.")
            integer("line", "1-based line to inspect.")
        },
        summary = { "quick fixes on ${it.optString("path") ?: "file"}:${it.optInt("line") ?: 0}" },
    ) { args ->
        val fixes = ws.quickFixes(args.string("path"), args.int("line"))
        if (fixes.isEmpty()) ToolExecutionResult.ok("No quick fixes available on that line.")
        else ToolExecutionResult.ok(fixes.joinToString("\n") { "[${it.index}] ${it.title} (${it.kind})" })
    },

    tool(
        name = "apply_quick_fix",
        description = "Apply the quick fix at the given index (from list_quick_fixes) on a line, editing the file.",
        parameters = toolSchema {
            string("path", "File path.")
            integer("line", "1-based line, matching the list_quick_fixes call.")
            integer("index", "The fix index reported by list_quick_fixes.")
        },
        mutating = true,
        summary = { "apply quick fix #${it.optInt("index") ?: 0}" },
    ) { args -> ToolExecutionResult.ok(ws.applyQuickFix(args.string("path"), args.int("line"), args.int("index"))) },

    tool(
        name = "format_file",
        description = "Reformat the whole file with the project's code style.",
        parameters = toolSchema { string("path", "File path.") },
        mutating = true,
        summary = { "format ${it.optString("path") ?: "file"}" },
    ) { args -> ToolExecutionResult.ok(ws.formatFile(args.string("path"))) },

    tool(
        name = "organize_imports",
        description = "Sort and remove unused imports in the file.",
        parameters = toolSchema { string("path", "File path.") },
        mutating = true,
        summary = { "organize imports in ${it.optString("path") ?: "file"}" },
    ) { args -> ToolExecutionResult.ok(ws.organizeImports(args.string("path"))) },

    // --- Build & dependencies ---

    tool(
        name = "list_tasks",
        description = "List the runnable build and run tasks for the project (run a Java main, assemble an APK, and " +
            "so on), each with the id to pass to run_task.",
        parameters = toolSchema { },
        summary = { "list tasks" },
    ) { _ ->
        val tasks = ws.listTasks()
        if (tasks.isEmpty()) ToolExecutionResult.ok("No tasks available.")
        else ToolExecutionResult.ok(tasks.joinToString("\n") { "${it.id}  —  ${it.label} [${it.group}]" })
    },

    tool(
        name = "run_task",
        description = "Run a build or run task by id (from list_tasks) and wait for it to finish, returning its " +
            "status, a trimmed log, and any diagnostics. Use this to build or assemble; use run_program to run a " +
            "console main and read its output.",
        parameters = toolSchema { string("id", "Task id from list_tasks.") },
        mutating = true,
        summary = { "run task ${it.optString("id").orEmpty()}" },
    ) { args -> formatTaskRun(ws.runTask(args.string("id"))) },

    tool(
        name = "search_dependency",
        description = "Search Maven Central and Google Maven for a library, returning matching coordinates " +
            "(group:name:version). Use a result with add_dependency.",
        parameters = toolSchema {
            string("query", "A name or partial coordinate, e.g. \"okhttp\" or \"androidx.compose\".")
            string("module", "Module to judge compatibility against.", required = false)
        },
        summary = { "search dependency \"${it.optString("query").orEmpty()}\"" },
    ) { args ->
        val hits = ws.searchDependency(args.string("query"), args.optString("module"))
        if (hits.isEmpty()) ToolExecutionResult.ok("No matching libraries found.")
        else ToolExecutionResult.ok(hits.joinToString("\n") { h ->
            "${h.coordinate} (${h.packaging})" + if (!h.compatible) "  [incompatible: ${h.note ?: "?"}]" else ""
        })
    },

    // --- Memory (persists across sessions) ---

    tool(
        name = "read_memory",
        description = "Read the agent's project memory: the project's own instruction files plus notes you have " +
            "saved for this project across sessions. Read this early to recall conventions and prior decisions.",
        parameters = toolSchema { },
        summary = { "read memory" },
    ) { _ -> ToolExecutionResult.ok(ws.readMemory().ifBlank { "(no memory saved yet)" }) },

    tool(
        name = "write_memory",
        description = "Save durable notes about this project for future sessions (conventions, architecture, " +
            "decisions). This replaces your saved notes, so include everything worth keeping. The project's own " +
            "instruction files are not affected.",
        parameters = toolSchema { string("content", "The full note text to persist (Markdown).") },
        summary = { "update project memory" },
    ) { args -> ToolExecutionResult.ok(ws.writeMemory(args.string("content"))) },

    // --- Web ---

    tool(
        name = "web_fetch",
        description = "Fetch a web page or raw file over HTTP(S) and return its readable text content (truncated). " +
            "Use it to read documentation, a changelog, or a URL referenced in the code or by the user.",
        parameters = toolSchema {
            string("url", "The absolute http(s) URL to fetch.")
            integer("max_chars", "Maximum characters to return (default 20000).", required = false)
        },
        summary = { "fetch ${it.optString("url").orEmpty()}" },
    ) { args -> ToolExecutionResult.ok(ws.fetchUrl(args.string("url"), args.optInt("max_chars") ?: 20_000)) },

    tool(
        name = "http_request",
        description = "Make an arbitrary HTTP(S) request, like curl: choose the method, headers, and body. " +
            "Returns the status line, key response headers, and the response body (truncated). Use web_fetch " +
            "for a simple GET of a page; use this when you need POST/PUT/PATCH/DELETE, custom headers, or a " +
            "request body (e.g. calling a REST API).",
        parameters = toolSchema {
            string("url", "The absolute http(s) URL.")
            string("method", "HTTP method.", required = false, enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
            stringArray("headers", "Request headers, each as \"Name: value\".", required = false)
            string("body", "Request body, for POST/PUT/PATCH.", required = false)
            integer("max_chars", "Maximum response characters to return (default 20000).", required = false)
        },
        // Outward-facing: a POST/PUT/DELETE can change remote state, so the user approves each request.
        mutating = true,
        summary = { "${it.optString("method")?.ifBlank { null } ?: "GET"} ${it.optString("url").orEmpty()}" },
    ) { args ->
        ToolExecutionResult.ok(
            ws.httpRequest(
                method = args.optString("method")?.ifBlank { null } ?: "GET",
                url = args.string("url"),
                headers = args.stringList("headers"),
                body = args.optString("body"),
                maxChars = args.optInt("max_chars") ?: 20_000,
            ),
        )
    },
)

/**
 * Converts a 1-based [line] (plus the optional [symbol] on it) to a character offset in the file's current
 * text. The symbol places the caret precisely on the identifier; without it the caret lands at the line
 * start. Out-of-range lines clamp to the end of the file.
 */
private suspend fun resolveOffset(ws: AgentWorkspace, path: String, line: Int, symbol: String?): Int {
    val text = ws.readFile(path)
    var offset = 0
    var current = 1
    while (current < line && offset < text.length) {
        val nl = text.indexOf('\n', offset)
        if (nl < 0) return text.length
        offset = nl + 1
        current++
    }
    if (symbol.isNullOrEmpty()) return offset
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val inLine = text.indexOf(symbol, offset)
    return if (inLine in offset until lineEnd) inLine else offset
}

private fun formatLocation(loc: Location): String {
    val where = if (loc.line > 0) "${loc.path}:${loc.line}:${loc.column}" else loc.path
    return if (loc.label.isNotBlank()) "$where  ${loc.label}" else where
}

private fun formatTaskRun(r: TaskRunResult): ToolExecutionResult {
    val sb = StringBuilder()
    sb.append(if (r.success) "Task succeeded" else "Task ${r.status}").append('.')
    if (r.diagnostics.isNotEmpty()) sb.append("\n\n--- diagnostics ---\n").append(r.diagnostics.joinToString("\n"))
    if (r.log.isNotBlank()) sb.append("\n\n--- log ---\n").append(r.log.trimEnd())
    return ToolExecutionResult(sb.toString(), isError = !r.success)
}

private fun formatRun(r: RunResult): ToolExecutionResult {
    if (!r.compiled) {
        val detail = if (r.diagnostics.isEmpty()) "" else "\n" + r.diagnostics.joinToString("\n")
        return ToolExecutionResult.error("Compilation failed; the program did not start.$detail")
    }
    val sb = StringBuilder()
    sb.append(
        if (r.finished) "Program finished with exit code ${r.exitCode ?: "unknown"}."
        else "Program did not finish (it timed out or is still waiting).",
    )
    if (r.output.isNotBlank()) sb.append("\n\n--- output ---\n").append(r.output.trimEnd())
    if (r.diagnostics.isNotEmpty()) sb.append("\n\n--- notes ---\n").append(r.diagnostics.joinToString("\n"))
    val failed = !r.finished || (r.exitCode != null && r.exitCode != 0)
    return ToolExecutionResult(sb.toString(), isError = failed)
}

private suspend fun editFile(ws: AgentWorkspace, args: ToolArgs): ToolExecutionResult {
    val path = args.string("path")
    val old = args.string("old_string")
    val new = args.string("new_string")
    val replaceAll = args.optBoolean("replace_all") ?: false
    if (old.isEmpty()) return ToolExecutionResult.error("old_string must not be empty.")
    val text = ws.readFile(path)
    val count = countOccurrences(text, old)
    if (count == 0) return ToolExecutionResult.error("old_string was not found in $path.")
    if (count > 1 && !replaceAll) {
        return ToolExecutionResult.error(
            "old_string occurs $count times in $path. Add surrounding context to make it unique, or set replace_all.",
        )
    }
    ws.applyEdits(path, buildReplaceEdits(text, old, new, replaceAll))
    return ToolExecutionResult.ok("Edited $path ($count replacement${if (count == 1) "" else "s"}).")
}

private fun countOccurrences(text: String, sub: String): Int {
    var index = text.indexOf(sub)
    var count = 0
    while (index >= 0) {
        count++
        index = text.indexOf(sub, index + sub.length)
    }
    return count
}

private fun buildReplaceEdits(text: String, old: String, new: String, all: Boolean): List<TextEdit> {
    val edits = ArrayList<TextEdit>()
    var index = text.indexOf(old)
    while (index >= 0) {
        edits += TextEdit(index, old.length, new)
        if (!all) break
        index = text.indexOf(old, index + old.length)
    }
    return edits
}

private fun tool(
    name: String,
    description: String,
    parameters: String,
    mutating: Boolean = false,
    summary: (ToolArgs) -> String = { name },
    action: suspend (ToolArgs) -> ToolExecutionResult,
): AgentTool = object : AgentTool {
    override val spec: ToolSpec = ToolSpec(name, description, parameters)
    override val mutating: Boolean = mutating
    override fun summarize(args: ToolArgs): String = summary(args)
    override suspend fun execute(args: ToolArgs): ToolExecutionResult = action(args)
}
