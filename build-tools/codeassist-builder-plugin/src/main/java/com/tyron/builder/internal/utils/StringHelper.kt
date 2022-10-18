@file:JvmName("StringHelper")
package com.tyron.builder.internal.utils

import com.google.common.base.CharMatcher
import com.google.common.collect.ImmutableList
import java.util.Locale
import java.util.regex.Pattern

private val CR = CharMatcher.`is`('\r')
private val LF = Pattern.compile("\n", Pattern.LITERAL)

/**
 * Returns this string capitalized.Appends the given <var>word</var> to the specified [StringBuilder].
 *
 *
 * The word is capitalized before being appended.
 *
 * @param sb the StringBuilder
 * @param word the word to add
 */
fun StringBuilder.appendCapitalized(word: String) : StringBuilder {
    if (word.isEmpty()) {
        return this
    }
    // manually compute the upper char of the first letter.
    // This avoids doing a substring(1).toUpperChar(Locale.US) which would create additional
    // objects.
    // This does not support characters that requires 2 char to be represented but the previous
    // code didn't either.
    // This catches possible errors and fallbacks to the less efficient way.
    var c = word[0].toInt()

    // see if the letter is using more than one char.
    if (c >= Character.MIN_HIGH_SURROGATE.toInt() && c <= Character.MAX_HIGH_SURROGATE.toInt()) {
        c = word.codePointAt(0)
        val charCount = Character.charCount(c)

        val upperString = word.substring(0, charCount).toUpperCase(Locale.US)
        append(upperString)
        append(word, charCount, word.length)
    } else {
        val result = Character.toUpperCase(c)
        val upperChar: Char

        // it's not clear where non surrogate-pair values can trigger this but this is safer.
        upperChar = if (result != -0x1) { //Character.ERROR (internal!)
            result.toChar()
        } else {
            word.substring(0, 1).toUpperCase(Locale.US)[0]
        }

        append(upperChar)
        append(word, 1, word.length)
    }

    return this
}

/**
 * Returns this string capitalized.
 *
 * This is unlikely to be what you need. Prefer to use [String.appendCapitalized], [StringBuilder.appendCapitalized] or [String.capitalizeAndAppend].
 *
 * @param word the word to be capitalized
 * @return the capitalized word.
 */
fun String.usLocaleCapitalize(): String {
    val sb = StringBuilder(length)
    sb.appendCapitalized(this)
    return sb.toString()
}

/**
 * Returns this string decapitalized, with US locale.
 */
fun String.usLocaleDecapitalize(): String {
    if (this.isEmpty()) return this
    val sb = StringBuilder(length)
    sb.append(this.substring(0, 1).toLowerCase(Locale.US))
    sb.append(this.substring(1))
    return sb.toString()
}

/**
 * Returns a string containing the given prefix, as is, and a capitalized version of the given
 * <var>word</var>.
 *
 * @param prefix the prefix to add before the word
 * @param word the word to be capitalized
 * @return the capitalized word.
 */
fun String.appendCapitalized(word: String): String {
    val sb = StringBuilder(length + word.length)
    sb.append(this)
    sb.appendCapitalized(word)
    return sb.toString()
}

/**
 * Returns a string containing the given prefix, as is, and a capitalized version of the given
 * <var>word1</var> and <var>word2</var>.
 *
 * @param prefix the prefix to add before the word
 * @param word1 the word to be capitalized
 * @param word2 the word to be capitalized
 * @return the capitalized word.
 */
fun String.appendCapitalized(word1: String, word2: String
): String {
    val sb = StringBuilder(length + word1.length + word2.length)
    sb.append(this)
    sb.appendCapitalized(word1)
    sb.appendCapitalized(word2)
    return sb.toString()
}

/**
 * Returns a string containing the given prefix, as is, and a capitalized version of the given
 * <var>words</var>.
 *
 * @param prefix the prefix to add before the words
 * @param words the words to be capitalized
 * @return the capitalized word.
 */
fun String.appendCapitalized(vararg words: String): String {
    var length = length

    var i = 0
    val max = words.size
    while (i < max) {
        length += words[i].length
        i++
    }
    val sb = StringBuilder(length)
    sb.append(this)
    for (word in words) {
        sb.appendCapitalized(word)
    }
    return sb.toString()
}

/**
 * Returns a capitalized version of the given <var>word</var>, including the given
 * <var>suffix</var>
 *
 * @param word the word to be capitalized
 * @param suffix the suffix to add after the word
 * @return the capitalized word.
 */
fun String.capitalizeAndAppend(suffix: String): String {
    val sb = StringBuilder(length + suffix.length)
    sb.appendCapitalized(this)
    sb.append(suffix)
    return sb.toString()
}

/**
 * Appends the given <var>work</var> to the specified StringBuilder in a camel case version.
 *
 *
 * if the builder is empty, the word is added as-is. If it's not then the work is capitalized
 *
 * @param sb the StringBuilder
 * @param word the word to add
 */
fun StringBuilder.appendCamelCase(word: String): StringBuilder {
    if (isEmpty()) {
        append(word)
    } else {
        appendCapitalized(word)
    }

    return this
}

fun Iterable<String>.combineAsCamelCase(): String {
    var count = 0
    for (s in this) {
        count += s.length
    }

    val sb = StringBuilder(count)
    var first = true
    for (str in this) {
        if (first) {
            sb.append(str)
            first = false
        } else {
            sb.appendCapitalized(str)
        }
    }
    return sb.toString()
}

fun <T> combineAsCamelCase(
    objectList: Collection<T>, mapFunction: (T) -> String
): String {
    val sb = StringBuilder(objectList.size * 20)
    combineAsCamelCase(sb, objectList, mapFunction)
    return sb.toString()
}

fun <T> combineAsCamelCase(
    sb: StringBuilder,
    objectList: Collection<T>,
    mapFunction: (T) -> String
) {

    var first = true
    for (obj in objectList) {
        if (first) {
            sb.append(mapFunction(obj))
            first = false
        } else {
            sb.appendCapitalized(mapFunction(obj))
        }
    }
}

/**
 * Returns a list of Strings containing the objects passed in argument.
 *
 *
 * If the objects are strings, they are directly added to the list. If the objects are
 * collections of strings, the strings are added. For other objects, the result of their
 * toString() is added.
 *
 * @param objects the objects to add
 * @return the list of objects.
 */
fun toStrings(vararg objects: Any): List<String> {
    val builder = ImmutableList.builder<String>()
    for (path in objects) {
        if (path is String) {
            builder.add(path)
        } else if (path is Collection<*>) {
            for (item in path) {
                if (item is String) {
                    builder.add(item)
                } else {
                    builder.add(path.toString())
                }
            }
        } else {
            builder.add(path.toString())
        }
    }

    return builder.build()
}

//fun String.tokenizeCommandLineToEscaped() =
//    TokenizedCommandLine(this, false).toTokenList()
//
//fun String.tokenizeCommandLineToRaw() =
//    TokenizedCommandLine(this, true).toTokenList()

fun String.toSystemLineSeparator(): String {
    return toLineSeparator(System.lineSeparator(), this)
}

private fun toLineSeparator(separator: String, input: String): String {
    val unixStyle = if (CR.matchesAnyOf(input)) CR.removeFrom(input) else input
    return if (separator == "\n") unixStyle else LF.matcher(unixStyle).replaceAll("\r\n")
}