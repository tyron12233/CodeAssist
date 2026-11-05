package dev.ide.core.plugins

import dev.ide.core.PluginSandbox
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogRecord

/**
 * One relayed log record as a line of text: tab-separated level, tag, source, message and stack trace, with
 * tabs, newlines and backslashes escaped so a record is always exactly one line.
 */
object SandboxLogLine {
    fun encode(record: LogRecord): String = listOf(
        record.level.name,
        record.tag,
        record.source.orEmpty(),
        record.message,
        record.throwable?.stackTraceToString().orEmpty(),
    ).joinToString("\t", postfix = "\n") { escape(it) }

    /** A line from the sandbox itself (not the plugin), shown under the plugin's source all the same. */
    fun encodeNote(source: String, message: String): String =
        listOf("WARN", "PluginSandbox", source, message, "").joinToString("\t", postfix = "\n") { escape(it) }

    /** Log [line] in this process as the sandbox's plugin logged it there. Malformed lines are dropped. */
    fun replay(line: String) {
        val parts = line.split('\t')
        if (parts.size != 5) return
        val (level, tag, source, message, stack) = parts.map(::unescape)
        val logger = Log.logger(tag, PluginSandbox.LOG_SOURCE_PREFIX + source.ifEmpty { "plugin" })
        val cause = stack.takeIf { it.isNotEmpty() }?.let(::RelayedThrowable)
        when (level) {
            "DEBUG" -> logger.debug(message)
            "INFO" -> logger.info(message)
            "WARN" -> logger.warn(message, cause)
            else -> logger.error(message, cause)
        }
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** The sandbox's exception as text: prints the stack trace it had there, since the classes may not exist here. */
    private class RelayedThrowable(private val text: String) : Throwable(text.lineSequence().firstOrNull(), null, false, false) {
        override fun printStackTrace(s: java.io.PrintWriter) = s.print(text)
        override fun printStackTrace(s: java.io.PrintStream) = s.print(text)
        override fun toString(): String = text.lineSequence().firstOrNull().orEmpty()
    }
}
