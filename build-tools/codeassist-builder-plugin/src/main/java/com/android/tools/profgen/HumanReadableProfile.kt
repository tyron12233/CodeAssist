package com.android.tools.profgen

import java.io.File
import java.io.InputStreamReader

internal const val HOT = 'H'
internal const val STARTUP = 'S'
internal const val POST_STARTUP = 'P'
internal const val WILDCARD_AST = '*'
internal const val WILDCARD_Q = '?'
internal const val COMMENT_START = '#'
internal const val JAVA_CLASS_START = 'L'
internal const val JAVA_CLASS_END = ';'
internal const val JAVA_CLASS_ARRAY_START = '['
internal const val OPEN_PAREN = '('
internal const val CLOSE_PAREN = ')'
internal const val METHOD_SEPARATOR_START = '-'
internal const val METHOD_SEPARATOR_END = '>'
internal const val INLINE_CACHE_SEPARATOR = '+'

/**
 * The in-memory representation of a human-readable set of profile rules.
 */
class HumanReadableProfile internal constructor(
    /**
     * The map of method descriptors to flags
     */
    private val exactMethods: Map<DexMethod, Int>,
    /**
     * The set of string descriptors that are included in the rules, specified to be "startup" classes. This is modeled
     * as a Set because the classes are either specified or aren't. These are "exact" in the sense that these classes
     * were specified in the file without any wildcards.
     *
     * These strings take the form `Lsome/package/name/ClassName;`
     */
    private val exactTypes: Set<String>,
    private val fuzzyMethods: MutablePrefixTree<ProfileRule>,
    private val fuzzyTypes: MutablePrefixTree<ProfileRule>
) {
    internal fun match(method: DexMethod): Int {
        var flags = exactMethods[method] ?: 0
        if (flags == MethodFlags.ALL) return flags
        val target = method.parent
        fuzzyMethods.prefixIterator(target).asSequence().takeWhile {
            if (it.matches(method)) {
                flags = flags or it.flags
            }
            flags != MethodFlags.ALL
        }.lastOrNull()
        return flags
    }

    internal fun match(type: String): Int {
        if (type in exactTypes) return MethodFlags.STARTUP
        val fuzzy = fuzzyTypes.firstOrNull(type) {
            it.target.matches(type)
        }
        return fuzzy?.flags ?: 0
    }
}

fun HumanReadableProfile(
    src: InputStreamReader,
    onError: (Int, Int, String) -> Unit
): HumanReadableProfile? {
    var failed = false
    val fragmentParser = RuleFragmentParser(80)
    val rules = src.readLines().mapIndexedNotNull { lineNumber, line ->
        val errorHandler: (Int, String) -> Unit = { columnNumber, message ->
            failed = true
            onError(lineNumber, columnNumber, message.withSnippet(line, columnNumber))
        }
        parseRule(line, errorHandler, fragmentParser)
    }
    if (failed) return null
    val exactMethods = mutableMapOf<DexMethod, Int>()
    val exactTypes =  mutableSetOf<String>()
    val fuzzyMethods = MutablePrefixTree<ProfileRule>()
    val fuzzyTypes = MutablePrefixTree<ProfileRule>()

    for (rule in rules) {
        when {
            rule.isExact && rule.isType -> exactTypes.add(rule.prefix)
            rule.isExact && !rule.isType -> exactMethods[rule.toDexMethod()] = rule.flags
            !rule.isExact && rule.isType -> fuzzyTypes.put(rule.prefix, rule)
            !rule.isExact && !rule.isType -> fuzzyMethods.put(rule.prefix, rule)
        }
    }

    return HumanReadableProfile(
        exactMethods,
        exactTypes,
        fuzzyMethods,
        fuzzyTypes,
    )
}

internal fun ProfileRule.toDexMethod(): DexMethod {
    // the matches here are expected to be exact.
    assert(isExact)
    assert(target.isExact)
    assert(method.isExact)
    assert(params.isExact)
    assert(returnType.isExact)
    return DexMethod(
        parent = target.prefix,
        name = method.prefix,
        prototype = DexPrototype(
            returnType = returnType.prefix,
            parameters = splitParameters(params.prefix),
        )
    )
}

fun interface Diagnostics {
    fun onError(error: String)
}

fun HumanReadableProfile(file: File,  diagnostics: Diagnostics): HumanReadableProfile? {
    return file.reader().use {
        HumanReadableProfile(it) { line, column, error ->
            diagnostics.onError("${file.name}:${line + 1}:${column + 1} error: $error")
        }
    }
}

internal fun String.withSnippet(line: String, column: Int) =
    "$this\n$line\n${" ".repeat(column)}^"


internal sealed class Part {
    class Exact(val value: String) : Part() {
        override fun toString(): String = value
    }
    open class Pattern(val pattern: String, val parsed: String) : Part() {
        override fun toString(): String = parsed
    }
    object WildChar : Pattern("[\\w<>\\[\\]]", "?")
    // Note that this regex will match `->` even though the HRP syntax will prevent wild parts from
    // matching with the method separator. This is okay because we only ever build regular
    // expressions to match the method name, parameters, and class name separately, so the parser
    // itself will guarantee that only a single `->` token per line is actually allowed.
    object WildPart : Pattern("[-\\w\\$<>\\[\\]]*", "*")
    object WildParts : Pattern("[-\\w\\$<>/;\\[\\]]*", "**")
}

internal class Flags(var flags: Int = 0)

private val MATCH_ALL_REGEX = Regex(".*")

internal class RuleFragmentParser(
    capacity: Int,
    private var parts: MutableList<Part> = mutableListOf()
) : Parseable(capacity) {
    fun wildCard(line: String, start: Int): Int {
        flushPart()
        return when (line[start]) {
            WILDCARD_AST -> {
                if (line[start + 1] == WILDCARD_AST) {
                    parts.add(Part.WildParts)
                    start + 2
                } else {
                    parts.add(Part.WildPart)
                    start + 1
                }
            }
            WILDCARD_Q -> {
                parts.add(Part.WildChar)
                start + 1
            }
            else -> illegalToken(line, start)
        }
    }

    fun flushPart() {
        if (sb.isNotEmpty()) {
            parts.add(Part.Exact(flush()))
        }
    }

    fun build(): RuleFragment {
        sb.clear()
        var exact = true
        var empty = true
        var prefix = ""
        val pattern: Regex
        for (i in parts.indices) {
            empty = false
            when (val part = parts[i]) {
                is Part.Exact -> sb.append(
                    if (exact) part.value
                    else Regex.escape(part.value)
                )
                is Part.Pattern -> {
                    if (exact) {
                        exact = false
                        prefix = sb.toString()
                        sb.clear()
                    }
                    sb.append(part.pattern)
                }
            }
        }
        if (exact) {
            prefix = sb.toString()
            pattern = MATCH_ALL_REGEX
        } else {
            pattern = Regex(sb.toString())
        }
        sb.clear()
        parts.clear()
        return RuleFragment(
            empty,
            exact,
            prefix,
            pattern,
        )
    }
}

internal class RuleFragment(
    val isEmpty: Boolean,
    val isExact: Boolean,
    val prefix: String,
    private val pattern: Regex,
) {
    fun matches(value: String): Boolean {
        return if (isExact) {
            prefix == value
        } else {
            value.startsWith(prefix) && pattern.matchEntire(value.substring(prefix.length)) != null
        }
    }

    companion object {
        val Empty = RuleFragment(true, true, "", MATCH_ALL_REGEX)
    }
}

internal class ProfileRule(
    val flags: Int,
    val target: RuleFragment,
    val method: RuleFragment,
    val params: RuleFragment,
    val returnType: RuleFragment,
) {
    val isExact = target.isExact && method.isExact && params.isExact && returnType.isExact
    val isType = method.isEmpty
    val prefix = target.prefix

    fun matches(other: DexMethod): Boolean {
        return target.matches(other.parent) &&
                method.matches(other.name) &&
                params.matches(other.parameters) &&
                returnType.matches(other.returnType)
    }
    override fun toString(): String {
        return buildString {
            append(target)
            append("->")
            append(method)
            append('(')
            append(params)
            append(')')
            append(returnType)
        }
    }
}

// line ::= flags target '->' method '(' params ')' type '\n'
internal fun parseRule(
    line: String,
    onError: (Int, String) -> Unit,
    fragmentParser: RuleFragmentParser
): ProfileRule? {
    if (line.isBlank()) {
        // empty lines are allowed, and should be skipped
        return null
    }
    var i = 0
    try {
        if (line[0] == COMMENT_START) {
            // If the line starts with a comment, the entire line gets skipped
            return null
        }
        if (line[0] == JAVA_CLASS_ARRAY_START) {
            // Line starts with class array from Android S+ which isn't supported by profgen
            return null
        }
        val flags = Flags().apply { i = parseFlags(line, i) }
        val targetIndex = i
        i = fragmentParser.parseTarget(line, i)
        val target = fragmentParser.build()
        // check if it has only target class
        if (i == line.length || line[i].isWhitespace()) {
            if (flags.flags != 0) {
                throw ParsingException(0, flagsForClassRuleMessage(line.substring(0, targetIndex)))
            }
            return ProfileRule(
                MethodFlags.STARTUP,
                target,
                RuleFragment.Empty,
                RuleFragment.Empty,
                RuleFragment.Empty
            )
        }
        i = consume(METHOD_SEPARATOR_START, line, i)
        i = consume(METHOD_SEPARATOR_END, line, i)
        i = fragmentParser.parseMethodName(line, i)
        val method = fragmentParser.build()
        i = consume(OPEN_PAREN, line, i)
        i = fragmentParser.parseParameters(line, i)
        val parameters = fragmentParser.build()
        i = consume(CLOSE_PAREN, line, i)
        i = fragmentParser.parseReturnType(line, i)
        val returnType = fragmentParser.build()
        if (i != line.length) {
            if (line[i] == INLINE_CACHE_SEPARATOR) {
                // Profgen doesn't support inline cache encoding from Android S+.
                return null
            }
            if (line.substring(i).isNotBlank()) {
                throw ParsingException(i, unexpectedTextAfterRule(line.substring(i)))
            }
        }
        if (flags.flags == 0) {
            throw ParsingException(0, emptyFlagsForMethodRuleMessage())
        }
        return ProfileRule(
            flags = flags.flags,
            target = target,
            method = method,
            params = parameters,
            returnType = returnType,
        )
    } catch (ex: ParsingException) {
        onError(ex.index, ex.message!!)
        return null
    }
}

// flags ::= ( 'H' | 'S' | 'P' )+
private fun Flags.parseFlags(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        flags = when (line[i]) {
            HOT -> flags or MethodFlags.HOT
            STARTUP -> flags or MethodFlags.STARTUP
            POST_STARTUP -> flags or MethodFlags.POST_STARTUP
            // termination
            WILDCARD_AST,
            WILDCARD_Q,
            JAVA_CLASS_START -> break
            else -> illegalToken(line, i)
        }
        i++
    }
    return i
}

// name_or_wild ::= ( word | '?' | '*' )+
// class_name ::= 'L' ( name_or_wild '/' )* name_or_wild ';'
private fun RuleFragmentParser.parseTarget(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        when (val c = line[i]) {
            WILDCARD_AST,
            WILDCARD_Q -> {
                i = wildCard(line, i)
            }
            METHOD_SEPARATOR_START -> {
                // don't consume the method separator token
                if (line[i + 1] == METHOD_SEPARATOR_END) break
                append(c)
                i++
            }
            JAVA_CLASS_END -> {
                append(c)
                i++
                break
            }
            OPEN_PAREN,
            CLOSE_PAREN,
            COMMENT_START -> illegalToken(line, i)
            else -> {
                append(c)
                i++
            }
        }
    }
    flushPart()
    return i
}

private fun RuleFragmentParser.parseMethodName(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        when (val c = line[i]) {
            WILDCARD_AST,
            WILDCARD_Q -> {
                i = wildCard(line, i)
            }
            METHOD_SEPARATOR_START -> {
                // don't consume the method separator token
                if (line[i + 1] == METHOD_SEPARATOR_END) illegalToken(line, i + 1)
                append(c)
                i++
            }
            OPEN_PAREN -> break
            JAVA_CLASS_END,
            CLOSE_PAREN,
            COMMENT_START -> illegalToken(line, i)
            else -> {
                append(c)
                i++
            }
        }
    }
    flushPart()
    return i
}

// primitive ::= 'Z' | 'B' | 'C' | 'S' | 'I' | 'J' | 'F' | 'D' | 'V'
// type ::= class_name | primitive
// params ::= type*
private fun RuleFragmentParser.parseParameters(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        when (val c = line[i]) {
            WILDCARD_AST,
            WILDCARD_Q -> {
                i = wildCard(line, i)
            }
            METHOD_SEPARATOR_START -> {
                // don't consume the method separator token
                if (line[i + 1] == METHOD_SEPARATOR_END) illegalToken(line, i + 1)
                append(c)
                i++
            }
            CLOSE_PAREN -> break
            OPEN_PAREN,
            COMMENT_START -> illegalToken(line, i)
            else -> {
                append(c)
                i++
            }
        }
    }
    flushPart()
    return i
}

// name_or_wild ::= ( word | '?' | '*' )+
// class_name ::= 'L' ( name_or_wild '/' )* name_or_wild ';'
private fun RuleFragmentParser.parseReturnType(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        when (val c = line[i]) {
            WILDCARD_AST,
            WILDCARD_Q -> {
                i = wildCard(line, i)
            }
            METHOD_SEPARATOR_START -> {
                // don't consume the method separator token
                if (line[i + 1] == METHOD_SEPARATOR_END) illegalToken(line, i)
                append(c)
                i++
            }
            JAVA_CLASS_END -> {
                append(c)
                i++
                break
            }
            INLINE_CACHE_SEPARATOR -> break
            OPEN_PAREN,
            CLOSE_PAREN,
            COMMENT_START -> illegalToken(line, i)
            else -> {
                if (c.isWhitespace()) break;
                append(c)
                i++
            }
        }
    }
    flushPart()
    return i
}
