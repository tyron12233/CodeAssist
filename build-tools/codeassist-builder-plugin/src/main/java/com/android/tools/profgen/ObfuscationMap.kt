package com.android.tools.profgen

import java.io.File
import java.io.InputStreamReader

/**
 * A data structure representing a subset of the information encoded in a Proguard Mapping file. This file provides the
 * information necessary to deobfuscate method named and class names. This file also contains a lot of information about
 * line mappings and inlining which is not attempted to be preserved here, as it is not important for profile generation
 */
class ObfuscationMap internal constructor(
    classMappings: List<ClassMapping>,
) {
    private val obfToOrig: Map<String, List<String>> =
        classMappings.groupBy({ it.obfuscated }, { it.original })

    private val origToObf = TypeMap(
        classMappings.associate { it.original to it.obfuscated }
    )

    private val methodObfToOrig: Map<DexMethod, DexMethod> = classMappings.flatMap { mapping ->
        mapping.originalToObfuscated.map { (original, obfName) ->
            val obfuscated = DexMethod(
                parent = origToObf[original.parent],
                name = obfName,
                prototype = DexPrototype(
                    returnType = origToObf[original.returnType],
                    parameters = original.prototype.parameters.map { origToObf[it] }
                )
            )
            obfuscated to original
        }
    }.toMap()

    internal fun deobfuscate(method: DexMethod): DexMethod {
        return methodObfToOrig[method] ?: method
    }

    internal fun deobfuscate(type: String): List<String> {
        return obfToOrig[type] ?: listOf(type)
    }

    companion object {
        val Empty = ObfuscationMap(emptyList())
    }
}

fun ObfuscationMap(src: InputStreamReader): ObfuscationMap {
    val lines = src.readLines()

    val mappings = mutableListOf<ClassMapping>()
    var currentClass = ClassMapping.Empty
    val memberParser = MemberParser(80)
    val classParser = ClassParser(80)
    val typeParser = TypeParser(80)

    for (i in lines.indices) {
        val line = lines[i]
        if (line.isEmpty()) continue
        when (line[0]) {
            '#' -> continue
            ' ' -> {
                memberParser.parseMemberLine(line, typeParser)
                currentClass.addMemberFromParser(memberParser)
                memberParser.clear()
            }
            else -> {
                if (currentClass != ClassMapping.Empty) mappings.add(currentClass)
                classParser.parse(line)
                currentClass = classParser.buildAndClear()
            }
        }
    }
    if (currentClass != ClassMapping.Empty) mappings.add(currentClass)

    return ObfuscationMap(mappings)
}

fun ObfuscationMap(file: File): ObfuscationMap {
    return file.reader().use { ObfuscationMap(it) }
}

/**
 * A small abstraction on top of a Map<String, String> where the mapping is understood to be a map of JVM type
 * signatures to other type signatures. In this case, this map will optimize the case where the passed in value is a
 * primitive type (ie, does NOT start with `L`) and will just return identity in that case. This avoids unnecessary map
 * lookups.
 */
internal class TypeMap(private val mapping: Map<String, String>) {
    operator fun get(value: String): String {
        if (value[0] == '[') {
            val index = value.indexOfFirst { it != '[' }
            return "[".repeat(index) + get(value.substring(index))
        }
        if (value[0] != 'L') return value
        return mapping[value] ?: value
    }
}

internal class ClassParser(capacity: Int): Parseable(capacity) {
    var before: String = ""
    var after: String = ""

    fun buildAndClear(): ClassMapping {
        val result = ClassMapping(before, after)
        before = ""
        after = ""
        return result
    }
}

internal class MemberParser(capacity: Int): Parseable(capacity) {
    var original = ""
    var obfuscated = ""
    var returnType = ""
    var isMethodRenaming = true
    var parameters = mutableListOf<String>()

    fun clear() {
        original = ""
        obfuscated = ""
        returnType = ""
        isMethodRenaming = true
        parameters.clear()
    }
}

internal class ClassMapping(
    val original: String,
    val obfuscated: String
) {
    /**
     * A map of each method of this class with their original names, and their corresponding mapped names
     */
    val originalToObfuscated = mutableMapOf<DexMethod, String>()

    internal fun addMemberFromParser(parser: MemberParser) {
        if (!parser.isMethodRenaming) return
        val method = DexMethod(
            parent = original,
            name = parser.original,
            prototype = DexPrototype(
                returnType = parser.returnType,
                parameters = parser.parameters.toList(),
            )
        )
        originalToObfuscated[method] = parser.obfuscated
    }
    companion object {
        val Empty = ClassMapping("", "")
    }
}

private class TypeParser(capacity: Int): Parseable(capacity) {
    var arrayDimensionsNumber: Int = 0
    var isObject: Boolean = false
    var descriptor: String = ""

    fun clear() {
        sb.clear()
        arrayDimensionsNumber = 0
        isObject = false
        descriptor = ""
    }
}

// identifier ::= \w | '$' | '-'
// fqn ::= identifier  ( '.' identifier )*
// type ::= fqn '[]'?
// parameters ::= type (',' type)*
// type_mapping ::= fqn ' -> ' fqn ':'
// field_mapping ::= type identifier ' -> ' identifier
// method_mapping ::= range? type fqn '(' parameters ')' range? ' -> ' identifier
private fun MemberParser.parseMemberLine(line: String, typeParser: TypeParser) {
    var i = 0
    i = whitespace(line, i)
    i = maybeSkipRange(line, i)
    i = typeParser.parseType(line, i)
    returnType = typeParser.descriptor
    i = consume(' ', line, i)
    i = parseIdentifier(line, i)
    original = flush()
    when (line[i]) {
        ' ' -> {
            // in this case it is a field name mapping.
            // for this, we don't need to parse anymore since we don't care
            // about field mappings
            isMethodRenaming = false
            return
        }
        '.' -> {
            // in this case, it is an inlined method
            // for this, we don't need to parse anymore since we don't care
            // about inlinings
            isMethodRenaming = false
            return
        }
        '(' -> {
            // this is a method renaming! this is what we care about, so we
            // continue to parse the rest
            isMethodRenaming = true
        }
        else -> illegalToken(line, i)
    }
    i = consume('(', line, i)
    i = parseParameters(line, i, typeParser)
    i = consume(')', line, i)
    i = maybeSkipRange(line, i)
    i = consume(" -> ", line, i)
    i = parseIdentifier(line, i)
    obfuscated = flush()
    require(i == line.length)
}

private fun MemberParser.parseParameters(line: String, start: Int, typeParser: TypeParser): Int {
    if (line[start] == ')') return start
    var i = start
    while (i < line.length) {
        when (line[i]) {
            ')' -> {
                parameters.add(typeParser.descriptor)
                break
            }
            ',' -> {
                parameters.add(typeParser.descriptor)
                i++
            }
            else -> i = typeParser.parseType(line, i)
        }
    }
    return i
}

private fun ClassParser.parse(line: String) {
    var i = 0
    i = parseFqn(line, i)
    before = flush()
    i = consume(" -> ", line, i)
    i = parseFqn(line, i)
    after = flush()
    i = consume(':', line, i)
    require(line.length == i)
}

private fun maybeSkipRange(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        val c = line[i]
        if (c != ':' && !c.isDigit()) break
        i++
    }
    return i
}

private fun Parseable.parseFqn(line: String, start: Int): Int {
    var i = start
    append('L')
    while (i < line.length) {
        when(val c = line[i]) {
            ' ', ':' -> break
            '.' -> {
                append('/')
            }
            else -> append(c)
        }
        i++
    }
    append(';')
    return i
}

private fun Parseable.parseIdentifier(line: String, start: Int): Int {
    var i = start
    while (i < line.length) {
        when(val c = line[i]) {
            ' ', '.', '(', '[' -> break
            else -> append(c)
        }
        i++
    }
    return i
}

private val PRIMITIVE_MAP = listOf(
    "boolean" to 'Z',
    "byte" to 'B',
    "char" to 'C',
    "short" to 'S',
    "int" to 'I',
    "long" to 'J',
    "float" to 'F',
    "double" to 'D',
    "void" to 'V',
).toMap()

private fun TypeParser.parseType(line: String, start: Int): Int {
    clear()
    var i = start
    while (i < line.length) {
        when (val c = line[i]) {
            ' ', ',', ')' -> break
            '[' -> {
                arrayDimensionsNumber++
                i = consume(']', line, i + 1)
                continue
            }
            '.' -> {
                isObject = true
                append('/')
            }
            else -> append(c)
        }
        i++
    }
    val result = flush()
    if (arrayDimensionsNumber > 0) append("[".repeat(arrayDimensionsNumber))
    val primitive = PRIMITIVE_MAP[result]
    if (primitive != null) {
        append(primitive)
    } else {
        isObject = true
        append('L')
        append(result)
        append(';')
    }
    descriptor = flush()
    return i
}
