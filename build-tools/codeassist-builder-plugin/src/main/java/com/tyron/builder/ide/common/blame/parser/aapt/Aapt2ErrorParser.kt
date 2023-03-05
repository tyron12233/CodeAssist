package com.tyron.builder.ide.common.blame.parser.aapt

import com.android.ide.common.blame.Message
import com.android.utils.ILogger
import com.tyron.builder.common.resources.isRelativeSourceSetResource
import com.tyron.builder.common.resources.relativeResourcePathToAbsolutePath
import com.tyron.builder.ide.common.blame.parser.ParsingFailedException
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader
import java.nio.file.FileSystems
import java.util.ArrayList
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Single line aapt2 error parser containing a path */
class Aapt2ErrorParser(val identifiedSourceSetMap: Map<String, String> = emptyMap()) :
    AbstractAaptOutputParser() {

    private val parsers = ArrayList<MessageParser>()

    init {
        // [ERROR: ]<path>:<line>:<colStart>-<colEnd> <error>
        parsers.add(object :
                        MessageParser(
                            "^(?:ERROR:\\s)?(.+?):(\\d+):(\\d+)-(\\d+)(?::)?\\s(.+)$",
                            identifiedSourceSetMap
                        ) {
            override fun getLineNumber(m: Matcher): String = m.group(2)
            override fun getColumnStart(m: Matcher): String = m.group(3)
            override fun getColumnEnd(m: Matcher): String = m.group(4)
            override fun getMessageText(m: Matcher): String = m.group(5)
        })

        // [ERROR: ]<path>:<line>:<column> <error>
        parsers.add(object :
                        MessageParser(
                            "^(?:ERROR:\\s)?(.+?):(\\d+):(\\d+)(?::)?\\s(.+)$",
                            identifiedSourceSetMap
                        ) {
            override fun getLineNumber(m: Matcher): String = m.group(2)
            override fun getColumnStart(m: Matcher): String = m.group(3)
            override fun getMessageText(m: Matcher): String = m.group(4)
        })

        // [ERROR: ]<path>:<line> <error>
        parsers.add(object :
                        MessageParser(
                            "^(?:ERROR:\\s)?(.+?):(\\d+)(?::)?\\s(.+)$",
                            identifiedSourceSetMap
                        ) {
            override fun getLineNumber(m: Matcher): String = m.group(2)
            override fun getMessageText(m: Matcher): String = m.group(3)
        })

        // [ERROR: ]<path> <error>
        parsers.add(object :
                        MessageParser(
                            "^(?:ERROR:\\s)?(.+?)(?::)?\\s(.+)$",
                            identifiedSourceSetMap
                        ) {
            override fun getMessageText(m: Matcher): String = m.group(2)
        })
    }

    /**
     * Parses the given output line.
     *
     * @param line the line to parse.
     * @param reader passed in case this parser needs to parse more lines in order to create a
     * `Message`.
     * @param messages stores the messages created during parsing, if any.
     * @return `true` if this parser was able to parser the given line, `false`
     * otherwise.
     * @throws ParsingFailedException if something goes wrong (e.g. malformed output.)
     */
    @Throws(ParsingFailedException::class)
    override fun parse(
        line: String,
        reader: OutputLineReader,
        messages: MutableList<Message>,
        logger: ILogger
    ): Boolean {
        for (parser in parsers) {
            val message = parser.parse(line, logger)
            if (message != null) {
                messages.add(message)
                return true
            }
        }
        return false
    }

    private abstract class MessageParser(pattern: String, val sourceSetMap: Map<String, String>) {

        private val pattern: Pattern = Pattern.compile(pattern)

        @Throws(ParsingFailedException::class)
        fun parse(line: String, logger: ILogger): Message? {
            val m = pattern.matcher(line)
            return if (!m.matches()) {
                null
            } else {
                val rawSourcePath = getSourcePath(m)
                // As rawSourcePath doesn't provide a relative resource filepath or a absolute
                // filepath, the rawSourcePath must be parsed in the event it is a relative path.
                val userReadableSourcePath = if (isRelativeSourceSetResource(rawSourcePath) &&
                    sourceSetMap.any()
                ) {
                    relativeResourcePathToAbsolutePath(
                        rawSourcePath,
                        sourceSetMap,
                        FileSystems.getDefault()
                    )
                } else {
                    rawSourcePath
                }
                createMessage(
                    Message.Kind.ERROR,
                    getMessageText(m),
                    userReadableSourcePath,
                    getLineNumber(m),
                    getColumnStart(m),
                    getColumnEnd(m),
                    "",
                    logger
                )
            }

        }

        protected abstract fun getMessageText(m: Matcher): String
        protected open fun getSourcePath(m: Matcher): String = m.group(1)
        protected open fun getLineNumber(m: Matcher): String? = null
        protected open fun getColumnStart(m: Matcher): String? = null
        protected open fun getColumnEnd(m: Matcher): String? = null
    }
}
