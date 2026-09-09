package dev.ide.core.settings

import dev.ide.lang.formatting.BracePlacement
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy

/**
 * A per-language code style profile (the Code Style screen edits one per language). A named [preset] fixes
 * everything to a known profile; the `custom` preset uses the fields below. Persisted per language by
 * [SettingsStore] under `codeStyle.<lang>.<field>`; resolved to the formatter's [FormatStyle] via
 * [toFormatStyle]. Field names mirror [FormatStyle]'s tunable knobs so the mapping is mechanical.
 *
 * The Kotlin re-indenter honors only indentation / tabs / blank-lines; the rest are Java-only (the Code
 * Style screen hides them on the Kotlin tab), so they are stored but ignored for Kotlin.
 */
data class CodeStyleSettings(
    val preset: String = PRESET_GOOGLE,
    val indentSize: Int = 2,
    val continuationIndent: Int = 4,
    val maxLineLength: Int = 100,
    val useTabs: Boolean = false,
    val braceStyle: String = BRACE_END_OF_LINE,
    val spaceBeforeParens: Boolean = true,
    val spaceWithinParens: Boolean = false,
    val spaceAfterComma: Boolean = true,
    val spaceAroundOperators: Boolean = true,
    val spaceBeforeBrace: Boolean = true,
    val blankLinesToKeep: Int = 1,
    // wrapping
    val wrapMethodParameters: String = WRAP_IF_LONG,
    val wrapMethodArguments: String = WRAP_IF_LONG,
    val wrapChainedCalls: String = WRAP_IF_LONG,
    val wrapBinaryExpressions: String = WRAP_IF_LONG,
    // blank lines
    val blankLinesAfterImports: Int = 1,
    val blankLinesBeforeMethod: Int = 1,
    val blankLinesBeforeField: Int = 0,
    val blankLinesBeforeFirstMember: Int = 0,
    val blankLinesBetweenTypes: Int = 1,
    // spacing
    val spaceBeforeSemicolon: Boolean = false,
    val spaceAroundLambdaArrow: Boolean = true,
    val spaceAroundTernary: Boolean = true,
    val spaceAfterTypeCast: Boolean = true,
    // comments
    val formatComments: Boolean = true,
    val wrapComments: Boolean = false,
) {

    fun toFormatStyle(): FormatStyle = when (preset) {
        PRESET_GOOGLE -> FormatStyle.GOOGLE
        PRESET_ANDROID -> FormatStyle.ANDROID
        PRESET_KOTLIN_OFFICIAL -> KOTLIN_OFFICIAL
        else -> FormatStyle(
            styleId = PRESET_CUSTOM,
            indentSize = indentSize,
            continuationIndent = continuationIndent,
            tabWidth = indentSize,
            useTabs = useTabs,
            maxLineLength = maxLineLength,
            bracePlacement = if (braceStyle == BRACE_NEXT_LINE) BracePlacement.NEXT_LINE else BracePlacement.END_OF_LINE,
            spaceBeforeControlParen = spaceBeforeParens,
            spaceWithinParens = spaceWithinParens,
            spaceAfterComma = spaceAfterComma,
            spaceAroundOperators = spaceAroundOperators,
            spaceBeforeBrace = spaceBeforeBrace,
            blankLinesToKeep = blankLinesToKeep,
            wrapMethodParameters = wrap(wrapMethodParameters),
            wrapMethodArguments = wrap(wrapMethodArguments),
            wrapChainedCalls = wrap(wrapChainedCalls),
            wrapBinaryExpressions = wrap(wrapBinaryExpressions),
            blankLinesAfterImports = blankLinesAfterImports,
            blankLinesBeforeMethod = blankLinesBeforeMethod,
            blankLinesBeforeField = blankLinesBeforeField,
            blankLinesBeforeFirstMember = blankLinesBeforeFirstMember,
            blankLinesBetweenTypes = blankLinesBetweenTypes,
            spaceBeforeSemicolon = spaceBeforeSemicolon,
            spaceAroundLambdaArrow = spaceAroundLambdaArrow,
            spaceAroundTernary = spaceAroundTernary,
            spaceAfterTypeCast = spaceAfterTypeCast,
            formatComments = formatComments,
            wrapComments = wrapComments,
        )
    }

    companion object {
        const val LANG_JAVA = "java"
        const val LANG_KOTLIN = "kotlin"

        const val PRESET_GOOGLE = "google"
        const val PRESET_ANDROID = "android"
        const val PRESET_KOTLIN_OFFICIAL = "kotlin_official"
        const val PRESET_CUSTOM = "custom"

        const val BRACE_END_OF_LINE = "endOfLine"
        const val BRACE_NEXT_LINE = "nextLine"

        const val WRAP_NEVER = "never"
        const val WRAP_IF_LONG = "ifLong"
        const val WRAP_ONE_PER_LINE = "onePerLine"

        const val MIN_INDENT = 1
        const val MAX_INDENT = 8
        const val MIN_CONTINUATION = 1
        const val MAX_CONTINUATION = 16
        const val MIN_LINE_LENGTH = 60
        const val MAX_LINE_LENGTH = 200
        const val MIN_BLANK = 0
        const val MAX_BLANK = 5

        /** Kotlin official style: 4-space indent, 8-space continuation. */
        val KOTLIN_OFFICIAL = FormatStyle(styleId = PRESET_KOTLIN_OFFICIAL, indentSize = 4, continuationIndent = 8, tabWidth = 4)

        /** The default profile for [languageId]: Java = Google (2-space), Kotlin = official (4-space). */
        fun default(languageId: String): CodeStyleSettings = when (languageId) {
            LANG_KOTLIN -> CodeStyleSettings(preset = PRESET_KOTLIN_OFFICIAL, indentSize = 4, continuationIndent = 8)
            else -> CodeStyleSettings(preset = PRESET_GOOGLE)
        }

        private fun wrap(id: String): WrapPolicy = when (id) {
            WRAP_NEVER -> WrapPolicy.NEVER
            WRAP_ONE_PER_LINE -> WrapPolicy.ONE_PER_LINE
            else -> WrapPolicy.IF_LONG
        }
    }
}
