package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtEscapeStringTemplateEntry
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * The literal errors K2 raises while it turns a literal's text into a value: an integer that does not fit a
 * `Long`, a lowercase `l` suffix, a misplaced `_`, leading zeros, a character literal that is empty, too long
 * or unterminated, and an escape sequence the language does not define.
 *
 * Ported from the raw FIR builder (`AbstractRawFirBuilder.generateConstantExpressionByLiteral`, and
 * `parseCharacter`/`escapedStringToCharacter` in `ConversionUtils.kt`), whose `DiagnosticKind`s
 * `coneDiagnosticToFirDiagnostic` maps to ILLEGAL_UNDERSCORE, INT_LITERAL_OUT_OF_RANGE, WRONG_LONG_SUFFIX,
 * INT_LITERAL_WITH_LEADING_ZEROS, EMPTY_CHARACTER_LITERAL, TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL,
 * INCORRECT_CHARACTER_LITERAL and ILLEGAL_ESCAPE. Each reads only the token's text, so none depends on
 * resolution and none can be wrong about a type.
 */
internal object KotlinLiteralChecks {

    fun constant(e: KtConstantExpression): Diagnostic? {
        val text = e.text
        val message = when (e.constantType) {
            KtNodeTypes.INTEGER_CONSTANT -> integerProblem(text)
            KtNodeTypes.FLOAT_CONSTANT -> if (hasIllegallyPositionedUnderscore(text, isFloatingPoint = true)) ILLEGAL_UNDERSCORE else null
            KtNodeTypes.CHARACTER_CONSTANT -> characterProblem(text)
            else -> null
        } ?: return null
        // K2 puts WRONG_LONG_SUFFIX on the suffix alone; the other literal errors cover the whole literal.
        val r = e.textRange
        val start = if (message == WRONG_LONG_SUFFIX) r.endOffset - 1 else r.startOffset
        return Diagnostic(TextRange(start, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.ILLEGAL_LITERAL)
    }

    /** An escape inside a string template, `"\x"` or `"\u00"`, which the escape table does not define. */
    fun escape(entry: KtEscapeStringTemplateEntry): Diagnostic? {
        if (escapedStringToCharacter(entry.text) != null) return null
        val r = entry.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, ILLEGAL_ESCAPE, KotlinDiagnosticCodes.ILLEGAL_LITERAL)
    }

    /** The checks in the order `generateConstantExpressionByLiteral` makes them: a misplaced underscore stops it
     *  first, then a value that does not parse, then a lowercase `l`; leading zeros override whatever came
     *  before, because that check runs last and replaces the diagnostic. */
    private fun integerProblem(text: String): String? {
        if (hasIllegallyPositionedUnderscore(text, isFloatingPoint = false)) return ILLEGAL_UNDERSCORE
        var problem: String? = when {
            parseLongLiteral(text) == null -> INT_LITERAL_OUT_OF_RANGE
            // A long or unsigned-long suffix spelled with a lowercase `l`.
            text.endsWith('l') -> WRONG_LONG_SUFFIX
            else -> null
        }
        if (text.length > 1 && text[0] == '0' && (text[1].isDigit() || text[1] == '_')) problem = INT_LITERAL_WITH_LEADING_ZEROS
        return problem
    }

    private fun hasUnsignedLongSuffix(text: String): Boolean =
        text.endsWith("ul") || text.endsWith("uL") || text.endsWith("Ul") || text.endsWith("UL")

    /** `parseNumericLiteral` for an integer: the value as a `Long` (an unsigned literal's bits), or null when it
     *  does not fit one. */
    private fun parseLongLiteral(raw: String): Long? {
        val text = raw.replace("_", "")
        val unsigned: Boolean
        val body: String
        when {
            hasUnsignedLongSuffix(text) -> { unsigned = true; body = text.dropLast(2) }
            text.endsWith('u') || text.endsWith('U') -> { unsigned = true; body = text.dropLast(1) }
            text.endsWith('l') || text.endsWith('L') -> { unsigned = false; body = text.dropLast(1) }
            else -> { unsigned = false; body = text }
        }
        val (digits, radix) = when {
            body.startsWith("0x") || body.startsWith("0X") -> body.substring(2) to 16
            body.startsWith("0b") || body.startsWith("0B") -> body.substring(2) to 2
            else -> body to 10
        }
        return if (unsigned) digits.toULongOrNull(radix)?.toLong() else digits.toLongOrNull(radix)
    }

    private val FP_LITERAL_PARTS = Regex("([_\\d]*)\\.?([_\\d]*)e?[+-]?([_\\d]*)[f]?")

    /** `hasIllegallyPositionedUnderscore` from the compiler's `ConstantExpressionUtils`: an underscore may only
     *  sit between digits, never at the start or end of the number or of one of its parts. */
    private fun hasIllegallyPositionedUnderscore(text: String, isFloatingPoint: Boolean): Boolean {
        val parts: List<String> = if (isFloatingPoint) {
            FP_LITERAL_PARTS.findAll(text).flatMap { it.groupValues }.toList()
        } else {
            var start = 0
            var end = text.length
            if (text.startsWith("0x", ignoreCase = true) || text.startsWith("0b", ignoreCase = true)) start += 2
            if (text.endsWith('l', ignoreCase = true)) --end
            if (start > end) return false
            listOf(text.substring(start, end))
        }
        return parts.any { it.startsWith("_") || it.endsWith("_") }
    }

    /** `parseCharacter`: what is wrong with a character literal's text, or null when it denotes one character. */
    private fun characterProblem(literal: String): String? {
        if (literal.length < 2 || literal[0] != '\'' || literal[literal.length - 1] != '\'') return INCORRECT_CHARACTER_LITERAL
        val text = literal.substring(1, literal.length - 1)
        if (text.isEmpty()) return EMPTY_CHARACTER_LITERAL
        return if (text[0] != '\\') {
            if (text.length == 1) null else TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL
        } else {
            if (escapedStringToCharacter(text) != null) null else ILLEGAL_ESCAPE
        }
    }

    /** `escapedStringToCharacter`: the character an escape sequence (starting with `\`) stands for, or null when
     *  the language defines no such escape. */
    private fun escapedStringToCharacter(text: String): Char? {
        if (text.isEmpty() || text[0] != '\\') return null
        val escape = text.substring(1)
        return when (escape.length) {
            1 -> ESCAPES[escape[0]]
            5 -> if (escape[0] == 'u') escape.substring(1).toIntOrNull(16)?.toChar() else null
            else -> null
        }
    }

    private val ESCAPES = mapOf(
        't' to '\t', 'b' to '\b', 'n' to '\n', 'r' to '\r',
        '\'' to '\'', '"' to '"', '\\' to '\\', '$' to '$',
    )

    private const val ILLEGAL_UNDERSCORE = "Incorrect usage of underscore in numeric literal"
    private const val INT_LITERAL_OUT_OF_RANGE = "Value out of range"
    private const val WRONG_LONG_SUFFIX = "Use 'L' instead of 'l'"
    private const val INT_LITERAL_WITH_LEADING_ZEROS = "Leading zeros are not allowed in integer literals"
    private const val INCORRECT_CHARACTER_LITERAL = "Incorrect character literal"
    private const val EMPTY_CHARACTER_LITERAL = "Empty character literal"
    private const val TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL = "Too many characters in a character literal"
    private const val ILLEGAL_ESCAPE = "Unsupported escape sequence"
}
