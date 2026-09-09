package dev.ide.lang.jdt.formatting

import dev.ide.lang.formatting.BracePlacement
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants
import kotlin.math.roundToInt

/**
 * Builds the Eclipse formatter option map for a [FormatStyle]. Starts from Eclipse's built-in defaults
 * (whose end-of-line brace placement already matches Google) and overrides the keys that define Google Java
 * Style plus the user-tunable knobs (indent / continuation / width / tabs / brace placement / inline
 * spacing / blank lines). The inline-spacing and brace families each cover dozens of context-specific JDT
 * keys; rather than enumerate them, we set every key whose id matches the family (the starting map already
 * contains them all), which is both concise and robust across JDT versions.
 */
internal object GoogleJavaStyle {

    fun options(style: FormatStyle, compliance: String): MutableMap<String, String> {
        val o = HashMap(DefaultCodeFormatterConstants.getEclipseDefaultSettings())

        // Parse at the module's language level so newer syntax (records, switch expressions) formats cleanly.
        o[JavaCore.COMPILER_COMPLIANCE] = compliance
        o[JavaCore.COMPILER_SOURCE] = compliance
        o[JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM] = compliance

        val indent = style.indentSize.coerceAtLeast(1)
        val tab = style.tabWidth.coerceAtLeast(1)
        if (style.useTabs) {
            o[DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR] = JavaCore.TAB
            o[DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE] = tab.toString()
            o[DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE] = tab.toString()
        } else {
            o[DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR] = JavaCore.SPACE
            o[DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE] = indent.toString()
            o[DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE] = indent.toString()
        }

        // Continuation indent is expressed in indentation UNITS; Google's +4 over a 2-space indent is 2 units.
        val units = (style.continuationIndent.toDouble() / indent).roundToInt().coerceAtLeast(1)
        o[DefaultCodeFormatterConstants.FORMATTER_CONTINUATION_INDENTATION] = units.toString()
        o[DefaultCodeFormatterConstants.FORMATTER_CONTINUATION_INDENTATION_FOR_ARRAY_INITIALIZER] = units.toString()

        val width = style.maxLineLength.coerceAtLeast(20).toString()
        o[DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT] = width
        // Comment text wraps at the width only when wrapping is on; otherwise leave comments unwrapped.
        o[DefaultCodeFormatterConstants.FORMATTER_COMMENT_LINE_LENGTH] = if (style.wrapComments) width else "9999"

        // Google indents the case bodies (and the cases) under the switch.
        o[DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_SWITCH] = DefaultCodeFormatterConstants.TRUE
        o[DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_CASES] = DefaultCodeFormatterConstants.TRUE

        // ---- brace placement (every brace_position_for_* key) ----
        val bracePos = if (style.bracePlacement == BracePlacement.NEXT_LINE) DefaultCodeFormatterConstants.NEXT_LINE
        else DefaultCodeFormatterConstants.END_OF_LINE
        o.bulk(bracePos) { it.contains(".brace_position_for_") }

        val insert = JavaCore.INSERT
        val skip = JavaCore.DO_NOT_INSERT

        // ---- space before control-statement parens (if/for/while/switch/catch/synchronized/try) ----
        val controls = listOf("_if", "_for", "_while", "_switch", "_catch", "_synchronized", "_try")
        o.bulk(if (style.spaceBeforeControlParen) insert else skip) { k ->
            k.contains("insert_space_before_opening_paren_in_") && controls.any { k.endsWith(it) }
        }

        // ---- spaces just inside parentheses: `( x )` ----
        val within = if (style.spaceWithinParens) insert else skip
        o.bulk(within) { it.contains("insert_space_after_opening_paren_in_") || it.contains("insert_space_before_closing_paren_in_") }

        // ---- space after comma (and never before it, the universal rule) ----
        o.bulk(if (style.spaceAfterComma) insert else skip) { it.contains("insert_space_after_comma_in_") }
        o.bulk(skip) { it.contains("insert_space_before_comma_in_") }

        // ---- spaces around binary + assignment operators ----
        // JDT 3.x splits the legacy "binary operator" into per-category keys (additive, multiplicative, …);
        // the old binary_operator key is ignored. We set every binary category plus assignment, but leave the
        // unary / prefix / postfix / not operators alone (Google never spaces `!x`, `-x`, `i++`).
        val op = if (style.spaceAroundOperators) insert else skip
        val binaryCats = listOf("additive", "multiplicative", "bitwise", "logical", "relational", "shift", "binary")
        o.bulk(op) { k -> k.contains("insert_space_") && binaryCats.any { k.endsWith("_${it}_operator") } }
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_ASSIGNMENT_OPERATOR] = op
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_ASSIGNMENT_OPERATOR] = op

        // ---- space before a `{` block brace ----
        o.bulk(if (style.spaceBeforeBrace) insert else skip) { it.contains("insert_space_before_opening_brace_in_") }

        // ---- blank lines kept between elements / inside bodies ----
        o[DefaultCodeFormatterConstants.FORMATTER_NUMBER_OF_EMPTY_LINES_TO_PRESERVE] = style.blankLinesToKeep.coerceAtLeast(0).toString()

        // ---- wrapping / line breaks (JDT alignment values are encoded ints) ----
        o[DefaultCodeFormatterConstants.FORMATTER_ALIGNMENT_FOR_PARAMETERS_IN_METHOD_DECLARATION] = align(style.wrapMethodParameters)
        o[DefaultCodeFormatterConstants.FORMATTER_ALIGNMENT_FOR_ARGUMENTS_IN_METHOD_INVOCATION] = align(style.wrapMethodArguments)
        o[DefaultCodeFormatterConstants.FORMATTER_ALIGNMENT_FOR_SELECTOR_IN_METHOD_INVOCATION] = align(style.wrapChainedCalls)
        o[DefaultCodeFormatterConstants.FORMATTER_ALIGNMENT_FOR_BINARY_EXPRESSION] = align(style.wrapBinaryExpressions)

        // ---- blank lines around elements ----
        o[DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_AFTER_IMPORTS] = style.blankLinesAfterImports.coerceAtLeast(0).toString()
        o[DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_BEFORE_METHOD] = style.blankLinesBeforeMethod.coerceAtLeast(0).toString()
        o[DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_BEFORE_FIELD] = style.blankLinesBeforeField.coerceAtLeast(0).toString()
        o[DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_BEFORE_FIRST_CLASS_BODY_DECLARATION] = style.blankLinesBeforeFirstMember.coerceAtLeast(0).toString()
        o[DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_BETWEEN_TYPE_DECLARATIONS] = style.blankLinesBetweenTypes.coerceAtLeast(0).toString()

        // ---- extra spacing ----
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_SEMICOLON] = if (style.spaceBeforeSemicolon) insert else skip
        val lambda = if (style.spaceAroundLambdaArrow) insert else skip
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_LAMBDA_ARROW] = lambda
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_LAMBDA_ARROW] = lambda
        val ternary = if (style.spaceAroundTernary) insert else skip
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_QUESTION_IN_CONDITIONAL] = ternary
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_QUESTION_IN_CONDITIONAL] = ternary
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_COLON_IN_CONDITIONAL] = ternary
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_COLON_IN_CONDITIONAL] = ternary
        o[DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_CLOSING_PAREN_IN_CAST] = if (style.spaceAfterTypeCast) insert else skip

        // ---- comment formatting ----
        val fmtComments = if (style.formatComments) DefaultCodeFormatterConstants.TRUE else DefaultCodeFormatterConstants.FALSE
        o[DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_JAVADOC_COMMENT] = fmtComments
        o[DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_BLOCK_COMMENT] = fmtComments
        o[DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_LINE_COMMENT] = fmtComments

        return o
    }

    /** Map the coarse [WrapPolicy] onto a JDT encoded alignment value (no forced split). */
    private fun align(policy: WrapPolicy): String {
        val wrapStyle = when (policy) {
            WrapPolicy.NEVER -> DefaultCodeFormatterConstants.WRAP_NO_SPLIT
            WrapPolicy.IF_LONG -> DefaultCodeFormatterConstants.WRAP_COMPACT
            WrapPolicy.ONE_PER_LINE -> DefaultCodeFormatterConstants.WRAP_ONE_PER_LINE
        }
        return DefaultCodeFormatterConstants.createAlignmentValue(false, wrapStyle, DefaultCodeFormatterConstants.INDENT_DEFAULT)
    }

    /** Set every option whose key matches [match] to [value] (the start map already holds the whole family). */
    private inline fun MutableMap<String, String>.bulk(value: String, match: (String) -> Boolean) {
        for (k in keys.toList()) if (match(k)) this[k] = value
    }
}
