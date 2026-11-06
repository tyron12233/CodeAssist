package com.zipcli.ke.domain

sealed interface ParseOutcome {
    data class Ok(val parsed: ParsedCommand) : ParseOutcome
    data class Err(val message: String) : ParseOutcome
}

/**
 * Turns one command into a [ParsedCommand]. Statement splitting (`;`, `&&`, newlines, loops)
 * and variable expansion happen before this, in the script layer; [parse] keeps the original
 * one-string entry point for callers that don't need them.
 */
class CommandParser(private val registry: CommandRegistry) {

    fun parse(input: String): ParseOutcome {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return ParseOutcome.Err("empty")
        val tokens = try { Lexer.words(autoFix(trimmed)).map { it.text } }
        catch (e: CommandParseException) { return ParseOutcome.Err(e.message ?: "Parse error") }
        return parseTokens(tokens, trimmed)
    }

    /** "update/home/a.txt" -> "update /home/a.txt" (only when the first word is a real command). */
    fun autoFix(statement: String): String {
        val m = Regex("^([^ /\"']+)(/)").find(statement) ?: return statement
        return if (registry.find(m.groupValues[1]) != null) statement.replaceFirst("/", " /") else statement
    }

    fun parseTokens(tokens: List<String>, raw: String): ParseOutcome {
        if (tokens.isEmpty()) return ParseOutcome.Err("empty")

        val spec = registry.find(tokens[0])
            ?: return ParseOutcome.Err(unknownCommand(tokens[0]))

        val flagsByShort = spec.flags.mapNotNull { f -> f.short?.let { it to f } }.toMap()
        val flagsByLong  = spec.flags.associateBy { it.long }

        fun unknownLong(key: String): ParseOutcome.Err {
            val hint = Suggest.closest(key, flagsByLong.keys)?.let { " Did you mean --$it?" }
                ?: " If it is literal text, write it after '--' or in quotes: ${spec.name} -- '<text>'"
            return ParseOutcome.Err("Unknown flag: --$key for '${spec.name}'.$hint")
        }

        /** "-z" is not a flag of this command: list the ones it does have, so the caller can fix it in one step. */
        fun unknownShort(c: Char): ParseOutcome.Err {
            val have = spec.flags.joinToString(", ") { f -> (f.short?.let { "-$it/" } ?: "") + "--" + f.long }
            val hint = if (have.isEmpty()) "It takes no flags." else "Flags: $have."
            return ParseOutcome.Err(
                "Unknown flag: -$c for '${spec.name}'. $hint If it is literal text, write it after '--' or in quotes. Try 'help ${spec.name}'.")
        }

        val positional = mutableListOf<String>()
        // positional index -> the boolean flag typed right before it ("-m 6": 6 is not the flag's value)
        val afterBoolFlag = mutableMapOf<Int, String>()
        var lastBool: String? = null
        val bools = mutableSetOf<String>()
        val values = mutableMapOf<String, String>()
        var i = 1

        while (i < tokens.size) {
            val t = tokens[i]
            val prevBool = lastBool
            lastBool = null
            when {
                // Text that only looks like a flag ("-- archive contents --", "-3") is a positional
                // argument: every command has a closed flag set, so such a token can never be a flag.
                t.length > 1 && t != "--" && t[0] == '-' && !looksLikeFlag(t) -> { positional += t; i++ }
                t == "--" -> {
                    i++
                    while (i < tokens.size) { positional += tokens[i]; i++ }
                }
                t.startsWith("--") && t.length > 2 -> {
                    val body = t.substring(2)
                    val eq = body.indexOf('=')
                    if (eq >= 0) {
                        val key = body.substring(0, eq)
                        val value = body.substring(eq + 1)
                        val f = flagsByLong[key] ?: return unknownLong(key)
                        if (!f.takesValue) return ParseOutcome.Err("--$key does not take a value")
                        values[key] = value; i++
                    } else {
                        val f = flagsByLong[body] ?: return unknownLong(body)
                        if (f.takesValue) {
                            if (i + 1 >= tokens.size)
                                return ParseOutcome.Err("--$body requires a value")
                            values[body] = tokens[i + 1]; i += 2
                        } else { bools += body; i++; lastBool = "--$body" }
                    }
                }
                t.startsWith("-") && t.length > 1 -> {
                    val body = t.substring(1)
                    if (body.length == 1) {
                        val f = flagsByShort[body[0]] ?: return unknownShort(body[0])
                        if (f.takesValue) {
                            if (i + 1 >= tokens.size)
                                return ParseOutcome.Err("-${body[0]} requires a value")
                            values[f.long] = tokens[i + 1]; i += 2
                        } else { bools += f.long; i++; lastBool = "-${body[0]}" }
                    } else {
                        for (c in body) {
                            val f = flagsByShort[c] ?: return unknownShort(c)
                            if (f.takesValue)
                                return ParseOutcome.Err("-$c takes a value and cannot be combined")
                            bools += f.long
                        }
                        i++
                    }
                }
                else -> {
                    prevBool?.let { afterBoolFlag[positional.size] = it }
                    positional += t; i++
                }
            }
        }

        if (positional.size < spec.minArgs || positional.size > spec.maxArgs) {
            val got = positional.size
            // "-m 6": a yes/no flag followed by a word is the usual cause of "too many arguments".
            if (got > spec.maxArgs) {
                for (idx in spec.maxArgs until got) {
                    afterBoolFlag[idx]?.let { flag ->
                        return ParseOutcome.Err(
                            "Usage: $flag takes no value (it is an on/off flag); the extra argument is '${positional[idx]}'. ${spec.usage}")
                    }
                }
            }
            return ParseOutcome.Err("Usage: ${spec.usage}  (got $got argument${if (got == 1) "" else "s"})")
        }

        return ParseOutcome.Ok(ParsedCommand(spec.name, positional, bools, values, raw.trim()))
    }

    /** True when [t] has the shape of a flag (-x, --name, --name=value), so real typos are still reported. */
    private fun looksLikeFlag(t: String): Boolean {
        val key = t.trimStart('-').substringBefore('=')
        return key.any { it.isLetter() } && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun unknownCommand(name: String): String {
        shellSyntaxHint(name)?.let { return it }
        if (name.lowercase() in APP_ONLY_COMMANDS && registry.find(name) == null) {
            return "'$name' is not available in this session: it is interactive and only runs in the app's own " +
                "terminal. (This is a policy, not a typo.) Run 'info' to see what is available here."
        }
        val hint = Suggest.closest(name, registry.names())
        return if (hint != null) "Unknown command: '$name'. Did you mean '$hint'?"
        else "Unknown command: '$name'. Type 'help'."
    }

    companion object {
        /** Commands that exist in the app but are never reachable over MCP (they need UI or manage the server). */
        val APP_ONLY_COMMANDS = setOf("saf", "mcp")

        const val NO_SHELL_FEATURES =
            "ZipCLI has no pipes, no redirection, no command substitution and no arithmetic. " +
                "Run commands as separate lines; use 'for' to loop. See 'help scripting'."

        /**
         * A "command" that is really shell syntax: a pasted prompt (`$`, `zipcli$`, `>`) or a bare operator.
         * That is a design boundary, not a typo, so it must not be reported as "Unknown command".
         */
        fun shellSyntaxHint(name: String): String? {
            if (name.isEmpty()) return null
            val promptLike = name.length <= 24 && name.last() in "$%>#" && name.dropLast(1).all { it.isLetterOrDigit() || it in "_-@:~/." }
            val operator = name.isNotEmpty() && name.all { it in "|<>&;()" }
            return when {
                promptLike -> "'$name' looks like a shell prompt, not a command. Paste the command without the prompt " +
                    "(without '$name'). $NO_SHELL_FEATURES"
                operator -> "'$name' is shell syntax and is not supported. $NO_SHELL_FEATURES"
                else -> null
            }
        }
    }
}
