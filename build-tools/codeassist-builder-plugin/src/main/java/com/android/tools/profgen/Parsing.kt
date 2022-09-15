package com.android.tools.profgen


internal class ParsingException(val index: Int, message: String) : Exception(message)

internal fun illegalToken(line: String, i: Int): Nothing {
    throw ParsingException(i, illegalTokenMessage(line[i]))
}

internal fun illegalTokenMessage(token: Char) = "Illegal token '$token'"

internal fun consume(char: Char, line: String, start: Int): Int {
    if (line.length <= start) {
        throw ParsingException(start, unexpectedEnd(char))
    }
    val actual = line[start]
    if (actual != char) {
        throw ParsingException(start, unexpectedChar(char, actual))
    }
    return start + 1
}

internal fun flagsForClassRuleMessage(flags: String) =
    "Class rules don't support flags, but '$flags' were specified"

internal fun emptyFlagsForMethodRuleMessage() =
    "At least one of flags 'H', 'S', 'P' must be specified for a method rule"

internal fun unexpectedChar(expected: Char, actual: Char) =
    "'$expected' is expected, but '$actual' was read"

internal fun unexpectedTextAfterRule(text: String) =
    "Unexpected text:\"$text\" after the rule."

internal fun unexpectedEnd(expected: Char) = "Rule ended, while '$expected' was expected"

internal fun consume(chars: String, line: String, start: Int): Int {
    var i = 0
    while (i < chars.length) {
        require(start + i < line.length)
        require(line[start + i] == chars[i])
        i++
    }
    return start + chars.length
}

internal fun whitespace(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        if (!line[i].isWhitespace()) break
        i++
    }
    return i
}

internal open class Parseable(capacity: Int) {
    val sb = StringBuilder(capacity)

    fun flush(): String {
        val result = sb.toString()
        sb.clear()
        return result
    }

    fun append(char: Char) {
        sb.append(char)
    }

    fun append(s: String) {
        sb.append(s)
    }
}
