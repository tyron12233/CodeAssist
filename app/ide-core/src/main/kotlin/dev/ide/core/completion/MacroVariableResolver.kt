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
internal class MacroVariableResolver : SnippetVariableResolver {
    override fun resolve(name: String, ctx: SnippetContext): String? {
        val text = ctx.document.text
        val offset = ctx.offset.coerceIn(0, text.length)
        return when (name.uppercase()) {
            "FILE", "FILENAME", "TM_FILENAME" -> ctx.document.file.name
            "CLASS", "FILE_BASE", "FILENAME_BASE" -> ctx.document.file.name.substringBeforeLast('.')
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
