package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
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
)

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
