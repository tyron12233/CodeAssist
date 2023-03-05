package com.tyron.builder.common.resources.escape.string

import com.google.common.escape.Escaper
import com.google.common.escape.Escapers

/**
 * Static singleton responsible for escaping string resources. See documentation for the
 * [StringResourceEscaper.escape] method for details.
 */
object StringResourceEscaper {
  /**
   * Escapes a string resource value in compliance with the
   * [rules](http://developer.android.com/guide/topics/resources/string-resource.html) and
   * [this Android Cookbook recipe](https://androidcookbook.com/Recipe.seam?recipeId=2219).
   *
   * The entire string is escaped as follows:
   *
   * 1. `'"'` and `'\\'` are escaped with backslashes
   * 1. `'\n'` and `'\t'` are escaped with `"\\n"` and `"\\t"`
   * 1. If the string starts or ends with a space, the string is quoted with `'"'`
   * 1. If the string does not start or end with a space, `'\''` is escaped with a backslash
   * 1. If the string starts with a `'?'` or `'@'`, that character is escaped with a backslash
   * 1. If escapeMarkupDelimiters is true, `'&'` and `'<'` are escaped with `"&amp;"` and `"&lt;"`
   *
   * If the string contains markup with attributes, the quotes will be escaped which will result in
   * invalid XML. If [escapeMarkupDelimiters] is true, the markup will lose its semantics and become
   * plain character data. If that is not desired, use
   * [com.android.ide.common.resources.escape.xml.CharacterDataEscaper.escape] which is XML-aware.
   *
   * @param escapeMarkupDelimiters if true escape `'&'` and `'<'` with their entity references
   */
  @JvmStatic
  @JvmOverloads
  fun escape(string: String, escapeMarkupDelimiters: Boolean = true): String {
    if (string.isEmpty()) return ""

    // Make the StringBuilder 50% larger as we may increase the number of characters.
    val builder = StringBuilder(string.length * 3 / 2)

    if (string.startsWith("?") || string.startsWith("@")) builder.append("""\""")

    val escaper: Escaper = buildEscaper(!string.startsOrEndsWithSpace(), escapeMarkupDelimiters)
    builder.append(escaper.escape(string))

    val s = builder.toString()
    return if (s.startsOrEndsWithSpace()) """"$s"""" else s
  }

  @Suppress("UnstableApiUsage")
  private fun buildEscaper(escapeApostrophes: Boolean, escapeMarkupDelimiters: Boolean): Escaper {
    val builder =
        Escapers.builder()
            .addEscape('"', """\"""")
            .addEscape('\\', """\\""")
            .addEscape('\n', """\n""")
            .addEscape('\t', """\t""")
    if (escapeApostrophes) builder.addEscape('\'', """\'""")
    if (escapeMarkupDelimiters) {
      builder.addEscape('&', "&amp;").addEscape('<', "&lt;")
    }
    return builder.build()
  }

  private fun String.startsOrEndsWithSpace(): Boolean = startsWith(" ") || endsWith(" ")
}