package com.tyron.builder.common.resources.escape.xml

import com.google.common.escape.Escapers
import com.tyron.builder.common.resources.escape.xml.CharacterDataEscaper.escape
import com.tyron.builder.common.resources.escape.xml.StringResourceContentHandler.STRING_ELEMENT_NAME
import com.tyron.builder.common.utils.XmlUtilsWorkaround
import org.openjdk.javax.xml.parsers.ParserConfigurationException
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import java.io.IOException
import java.io.StringReader
import java.util.regex.Pattern

/**
 * Static singleton responsible for escaping character data in XML. See the [escape] method for more
 * details.
 */
object CharacterDataEscaper {
  private const val DECIMAL_ESCAPE = "___D"
  private const val HEXADECIMAL_ESCAPE = "___X"
  private val DECIMAL_REFERENCE = Pattern.compile("""&#(\d+);""")
  private val HEXADECIMAL_REFERENCE = Pattern.compile("""&#x(\p{XDigit}+);""")
  private val ESCAPED_DECIMAL_REFERENCE = Pattern.compile("""$DECIMAL_ESCAPE(\d+);""")
  private val ESCAPED_HEXADECIMAL_REFERENCE =
      Pattern.compile("""$HEXADECIMAL_ESCAPE(\p{XDigit}+);""")
  private val saxParserFactory =
      XmlUtilsWorkaround.configureSaxFactory(
          org.openjdk.javax.xml.parsers.SAXParserFactory.newInstance(), /* namespaceAware= */ false, /* checkDtd= */ false)

  /**
   * Map from characters that need unescaping to what we replace them with when we unescape them,
   * which may be the same character.
   */
  private val escapedCharReplacements =
      mapOf(
          ' ' to ' ',
          '"' to '"',
          '\'' to '\'',
          '\\' to '\\',
          'n' to '\n',
          't' to '\t',
      )

  /**
   * Escapes a string resource value in compliance with the
   * [rules](http://developer.android.com/guide/topics/resources/string-resource.html) and
   * [this Android Cookbook recipe](https://androidcookbook.com/Recipe.seam?recipeId=2219).
   *
   * The argument is expected to be valid XML. Character data outside of CDATA sections is escaped
   * as follows:
   *
   * 1. `'"'` and `'\\'` are escaped with backslashes
   * 1. `'\n'` and `'\t'` are escaped with `"\\n"` and `"\\t"`
   * 1. If the string starts or ends with a space, the string is quoted with `'"'`
   * 1. If the string does not start or end with a space, `'\''` is escaped with a backslash
   * 1. If the string starts with a `'?'` or `'@'`, that character is escaped with a backslash
   *
   * @throws IllegalArgumentException If the XML is not valid
   */
  @JvmStatic
  fun escape(string: String): String {
    if (string.isEmpty()) return ""

    val xml = string.escapeCharacterReferences()
    // Initial size 50% larger due to likelihood of adding more characters when escaping.
    val builder: StringBuilder = StringBuilder(xml.length * 3 / 2)

    if (xml.startsWith("?") || xml.startsWith("@")) builder.append("""\""")

    createEscapingContentHandler(builder, !xml.startsOrEndsWithSpace()).parse(xml)

    val s = builder.toString().unescapeCharacterReferences()

    return if (s.startsOrEndsWithSpace()) """"$s"""" else s
  }

  /**
   * Unescapes a string resource value in compliance with the
   * [rules](https://developer.android.com/guide/topics/resources/string-resource.html) and
   * [this Android Cookbook recipe](https://androidcookbook.com/Recipe.seam?recipeId=2219)
   *
   * The argument is expected to be valid XML. Character data outside of CDATA sections is unescaped
   * as follows:
   *
   * 1. If the string starts with `"\\?"` or `"\\@"`, that substring is unescaped
   * 1. Unescaped quotation marks are stripped
   * 1. `"\\ "`, `"\\\""`, `"\\'"`, and `"\\\\"` are unescaped
   * 1. `"\\n"` and `"\\t"` are unescaped to `"\n"` and `"\t"`
   *
   * @throws IllegalArgumentException If the XML is not valid
   */
  @JvmStatic
  fun unescape(string: String): String {
    if (string.isEmpty()) return ""

    val xml = string.escapeCharacterReferences().unescapeLeadingQuestionMarkOrAtSign()

    val builder: StringBuilder = StringBuilder(xml.length)

    createUnescapingContentHandler(builder).parse(xml)

    return builder.toString().unescapeCharacterReferences()
  }

  /**
   * Returns a [ContentHandler] that appends the escaped content to the end of the provided
   * [StringBuilder].
   */
  @Suppress("UnstableApiUsage")
  private fun createEscapingContentHandler(
      stringBuilder: StringBuilder,
      escapeApostrophes: Boolean
  ): ContentHandler {
    val builder =
        Escapers.builder()
            .addEscape('"', """\"""")
            .addEscape('\\', """\\""")
            .addEscape('\n', """\n""")
            .addEscape('\t', """\t""")
    if (escapeApostrophes) builder.addEscape('\'', """\'""")
    val escaper = builder.build()

    return StringResourceContentHandler(stringBuilder) { stringBuilder.append(escaper.escape(it)) }
  }

  /**
   * Returns a [ContentHandler] that appends the content to the given [StringBuilder] after
   * stripping escaped quotes and unescaping each character.
   */
  private fun createUnescapingContentHandler(stringBuilder: StringBuilder): ContentHandler =
      StringResourceContentHandler(stringBuilder) {
        stringBuilder.append(it.stripUnescapedQuotes().unescapeChars())
      }

  /** Returns the replacement character for this character per [escapedCharReplacements]. */
  private fun Char.getReplacement(): Char =
      escapedCharReplacements[this] ?: throw IllegalArgumentException(toString())

  /** Returns a version of the [String] with unescaped quote characters removed. */
  private fun String.stripUnescapedQuotes(): String = filterIndexed { i, c ->
    c != '"' || containsEscapedCharAt(i)
  }

  /** Unescapes the [String] character by character as required. */
  private fun String.unescapeChars(): String =
      mapIndexedNotNull { i, c ->
            when {
              i < length - 1 && shouldUnescapeCharAt(i + 1) -> null // Elide escape char
              shouldUnescapeCharAt(i) -> c.getReplacement()
              else -> c
            }
          }
          .joinToString("")

  private fun String.startsOrEndsWithSpace(): Boolean = startsWith(" ") || endsWith(" ")

  /** Returns `true` iff we have a replacement for the character at [index] and it is escaped. */
  private fun String.shouldUnescapeCharAt(index: Int): Boolean =
      get(index) in escapedCharReplacements && containsEscapedCharAt(index)

  /**
   * Determines if the character at index [index] is escaped.
   *
   * A character is considered escaped if the previous character is an *unescaped* backslash. The
   * function calls itself (tail) recursively if it needs to determine whether the previous
   * character is itself escaped.
   */
  private tailrec fun String.containsEscapedCharAt(index: Int, negated: Boolean = false): Boolean {
    if (index in 1..length && get(index - 1) == '\\') {
      return containsEscapedCharAt(index - 1, !negated)
    }
    return negated
  }

  private fun String.unescapeLeadingQuestionMarkOrAtSign(): String =
      if (startsWith("""\?""") || startsWith("""\@""")) substring(1) else this

  /**
   * Returns a copy of this [String] with all decimal and hexadecimal character references escaped.
   */
  private fun String.escapeCharacterReferences(): String {
    val s = DECIMAL_REFERENCE.matcher(this).replaceAll("$DECIMAL_ESCAPE$1;")
    return HEXADECIMAL_REFERENCE.matcher(s).replaceAll("$HEXADECIMAL_ESCAPE$1;")
  }

  /**
   * Returns a copy of this [String] with all escaped decimal and hexadecimal character references
   * unescaped.
   */
  private fun String.unescapeCharacterReferences(): String {
    val s = ESCAPED_DECIMAL_REFERENCE.matcher(this).replaceAll("&#$1;")
    return ESCAPED_HEXADECIMAL_REFERENCE.matcher(s).replaceAll("&#x$1;")
  }

  /**
   * Builds a [javax.xml.parsers.SAXParser], sets `this` [ContentHandler] as its handler and then
   * uses the parser to parse the [String].
   *
   * @throws IllegalArgumentException if [string] is not valid XML
   */
  private fun ContentHandler.parse(string: String) {
    val reader: XMLReader

    try {
      reader =
          XmlUtilsWorkaround.createSaxParser(saxParserFactory).xmlReader.apply {
            contentHandler = this@parse
            setProperty("http://xml.org/sax/properties/lexical-handler", this@parse)
          }
    } catch (exception: ParserConfigurationException) {
      throw RuntimeException(exception)
    } catch (exception: SAXException) {
      throw RuntimeException(exception)
    }

    val xml = "<$STRING_ELEMENT_NAME>$string</$STRING_ELEMENT_NAME>"

    try {
      reader.parse(InputSource(StringReader(xml)))
    } catch (exception: IOException) {
      throw AssertionError(exception)
    } catch (exception: SAXException) {
      throw IllegalArgumentException(xml, exception)
    }
  }
}