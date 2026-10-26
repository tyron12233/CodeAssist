package dev.ide.core.completion

import dev.ide.lang.template.SnippetContext
import dev.ide.lang.template.SnippetVariableResolver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Resolves a macro template's variables at expansion time — file, current expression, date/time, user, and a
 * fresh UUID. Accepts the user-facing IntelliJ-style names ([dev.ide.core.customize.MacroVariables.NAMES]) plus
 * a few TextMate aliases (`TM_FILENAME`, `CURRENT_YEAR`, …). Unknown → null so the engine falls back to the
 * segment's default, then to the empty string. Reads the live clock/system, so `$DATE$`/`$UUID$` reflect the
 * moment of expansion.
 */
private const val FUNCTION_SCAN_LIMIT = 100_000
private val CONTROL_WORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "try", "when")
private val TYPE_WORDS = setOf("class", "interface", "object", "enum", "record")

internal class MacroVariableResolver : SnippetVariableResolver {
    override fun resolve(name: String, ctx: SnippetContext): String? {
        val text = ctx.document.text
        val offset = ctx.offset.coerceIn(0, text.length)
        return when (name.uppercase()) {
            "FILE", "FILENAME", "TM_FILENAME" -> ctx.document.file.name
            "CLASS", "FILE_BASE", "FILENAME_BASE" -> ctx.document.file.name.substringBeforeLast('.')
            "METHOD_NAME", "FUNCTION_NAME" -> enclosingFunctionName(text, offset).orEmpty()
            "FILEPATH", "TM_FILEPATH", "TM_DIRECTORY" -> ctx.document.file.path
            "EXPR", "CURRENT_EXPRESSION", "WORD", "CURRENT_WORD" -> expressionBefore(text, offset)
            "SELECTION", "TM_SELECTED_TEXT" -> {
                val sel = ctx.selection ?: return ""
                text.subSequence(sel.start.coerceIn(0, text.length), sel.end.coerceIn(0, text.length)).toString()
            }
            "LINE", "TM_LINE_NUMBER" -> (text.subSequence(0, offset).count { it == '\n' } + 1).toString()
            "INDENT" -> ctx.indent
            "DATE", "CURRENT_DATE" -> LocalDate.now().toString()
            "TIME", "CURRENT_TIME" -> LocalTime.now().withNano(0).toString()
            "DATETIME" -> LocalDateTime.now().withNano(0).toString()
            "YEAR", "CURRENT_YEAR" -> LocalDate.now().year.toString()
            "MONTH", "CURRENT_MONTH" -> "%02d".format(LocalDate.now().monthValue)
            "DAY", "CURRENT_DAY" -> "%02d".format(LocalDate.now().dayOfMonth)
            "USER", "USERNAME" -> System.getProperty("user.name")
            "UUID" -> UUID.randomUUID().toString()
            else -> null
        }
    }

    /**
     * The name of the function or method enclosing [offset]: the nearest unclosed `{` whose header declares one
     * (`fun name(…)` in Kotlin, `Type name(…)` in Java), passing over control blocks, lambdas and calls with a
     * trailing lambda (`Column(…) {`). A light text scan, so it can be fooled by braces in strings.
     */
    private fun enclosingFunctionName(text: CharSequence, offset: Int): String? {
        var depth = 0
        var i = offset.coerceIn(0, text.length) - 1
        val limit = maxOf(0, offset - FUNCTION_SCAN_LIMIT)
        while (i >= limit) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else declaredName(text, i)?.let { return it }
            }
            i--
        }
        return null
    }

    /** The function name declared by the header ending at the `{` at [brace], or null if it declares none. */
    private fun declaredName(text: CharSequence, brace: Int): String? {
        var start = brace
        while (start > 0 && text[start - 1] != ';' && text[start - 1] != '{' && text[start - 1] != '}') start--
        val header = text.subSequence(start, brace).toString()
        val open = header.indexOf('(')
        if (open <= 0) return null
        val words = header.substring(0, open).split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() && !it.startsWith("@") }
        val name = words.lastOrNull()?.substringAfterLast('.') ?: return null
        if (words.any { it in TYPE_WORDS }) return null // a class's own header (`class A(val x: Int) {`)
        val declares = "fun" in words || (words.size >= 2 && words[words.size - 2] != "new" && name !in CONTROL_WORDS)
        return name.takeIf { declares && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '$' } }
    }

    /** The identifier / dotted expression immediately before [offset] (skipping trailing whitespace) — the
     *  "current expression" a macro can reference (empty at a statement start). */
    private fun expressionBefore(text: CharSequence, offset: Int): String {
        var i = offset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        val end = i + 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.' || text[i] == '$')) i--
        val start = (i + 1).coerceAtMost(end)
        return text.subSequence(start, end).toString()
    }
}
