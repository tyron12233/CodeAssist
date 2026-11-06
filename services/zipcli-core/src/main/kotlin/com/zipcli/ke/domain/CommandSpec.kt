package com.zipcli.ke.domain

import com.zipcli.ke.fs.TerminalFileSystem

data class FlagSpec(
    val long: String,
    val short: Char? = null,
    val takesValue: Boolean = false,
    val description: String = "",
)

data class CommandContext(
    val parsed: ParsedCommand,
    val fs: TerminalFileSystem,
    val cwd: String,
    val history: List<String>,
    val session: ShellSession = ShellSession(cwd),
    val launcher: ScriptLauncher? = null,
    /** Where the command runs: "terminal" (the app) or "mcp" (a remote client). Shown by `info`. */
    val transport: String = "terminal",
    /** Report a short status line for long-running work (shown next to the spinner, not logged). */
    val progress: (String) -> Unit = {},
)

data class CommandSpec(
    val name: String,
    val aliases: List<String> = emptyList(),
    val summary: String,
    val usage: String,
    val flags: List<FlagSpec> = emptyList(),
    val minArgs: Int = 0,
    val maxArgs: Int = Int.MAX_VALUE,
    /** When true, unquoted `*` / `?` / `**` in arguments are expanded to matching paths before the command runs. */
    val expandGlobs: Boolean = false,
    val execute: suspend (CommandContext) -> CommandResult,
    /** One line describing the exit codes, shown by 'help <command>'. */
    val exitStatus: String? = null,
    /** True when the command can ask for confirmation, so the global `--yes` / `-y` flag applies to it. */
    val mayConfirm: Boolean = false,
) {
    val names: List<String> get() = listOf(name) + aliases
}
