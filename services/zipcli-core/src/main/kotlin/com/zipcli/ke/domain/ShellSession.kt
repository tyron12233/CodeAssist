package com.zipcli.ke.domain

/**
 * Mutable state that survives between commands: working directory, variables and shell options.
 * The terminal keeps one for the whole session; `run` gives every script its own copy
 * (so a script's `cd` / `set` never leaks into the caller, like a sub-shell).
 */
class ShellSession(var cwd: String = "/home") {
    val vars: MutableMap<String, String> = LinkedHashMap()

    /** Stop at the first failing command (like `set -e`). On by default: safer for file operations. */
    var errexit: Boolean = true

    /** Print every command before it runs (like `set -x`). */
    var trace: Boolean = false

    var lastStatus: Int = 0

    /** Answer yes to every confirmation prompt (`set -y`). Off by default. */
    var autoYes: Boolean = false

    /** 0 for the interactive prompt, +1 for every nested `run`. */
    var depth: Int = 0

    /** Name of the script being executed (for error locations), null at the prompt. */
    var scriptName: String? = null

    fun lookup(name: String): String? = when (name) {
        "?" -> lastStatus.toString()
        "PWD", "CWD" -> cwd
        else -> vars[name] ?: if (name == "HOME") "/home" else null
    }

    fun child(): ShellSession = ShellSession(cwd).also {
        it.vars.putAll(vars)
        it.errexit = true
        it.trace = trace
        it.depth = depth + 1
    }
}

/** Lets the `run` command start a script without knowing about the runner. */
interface ScriptLauncher {
    /** Returns the exit status (0 = success). */
    suspend fun runFile(parent: ShellSession, path: String, args: List<String>, autoYes: Boolean): Int

    /** Parses [text] as a script without running anything and returns one line per problem found (empty = fine). */
    suspend fun check(text: String): ScriptCheck
}

/** Result of `check`: [problems] are ready to print, [commands] is how many commands were inspected. */
data class ScriptCheck(val problems: List<String>, val commands: Int)
