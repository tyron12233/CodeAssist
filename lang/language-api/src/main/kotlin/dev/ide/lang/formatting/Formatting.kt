package dev.ide.lang.formatting

import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile

/**
 * Code-formatting SPI: the "reflow source to a consistent style" layer over a tolerant parse.
 *
 * A backend turns a buffer into the minimal set of [DocumentEdit]s that bring it to the requested
 * [FormatStyle]. The host applies those edits through the same surgical edit path quick-fixes use, so
 * untouched lines keep their byte ranges (caret/scroll survive). Formatting is one-shot and text-driven:
 * the backend re-parses [text] itself rather than relying on the analyzer's incremental state.
 *
 * Extensible the same way every other editor capability is: a backend opts in by advertising
 * [dev.ide.lang.BackendCapability.FORMAT] and returning a [FormattingService] from its analyzer (see
 * [dev.ide.lang.SourceAnalyzer.formatting]). Adding a language's formatter is a backend registration, not a
 * host edit.
 */
interface FormattingService {
    /**
     * The minimal edits that reformat the whole [text] of [file] to [style]. Returns an empty list when the
     * buffer is already formatted, or when the backend can't safely format it (e.g. an unrecoverable parse).
     * Runs on the shared engine thread.
     */
    suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit>

    /**
     * Reformat only the text overlapping [range] (document offsets `[start, end)`). The default formats the
     * whole file; a backend overrides it to confine edits to the selected span.
     */
    suspend fun formatRange(
        file: VirtualFile,
        text: CharSequence,
        range: TextRange,
        style: FormatStyle,
    ): List<DocumentEdit> = format(file, text, style)
}

/** Where a `{` block-opening brace goes relative to the statement that introduces it. */
enum class BracePlacement { END_OF_LINE, NEXT_LINE }

/** How a long construct (argument list, chained call, binary expression) is wrapped across lines. */
enum class WrapPolicy {
    /** Never split; let the line run long. */
    NEVER,

    /** Wrap only when the line exceeds the width, keeping as much as fits per line. */
    IF_LONG,

    /** When it must wrap, put each element on its own line. */
    ONE_PER_LINE,
}

/**
 * The style knobs a formatter honors. The host derives these from the Code Style settings; the defaults are
 * Google's Java conventions (2-space indent, 4-space continuation, 100-column line, spaces not tabs,
 * end-of-line braces, spaces after commas / around operators). A backend maps them onto its own engine: the
 * JDT (Java) formatter honors all of them; the Kotlin re-indenter honors only the layout knobs it can apply
 * without reflowing ([indentSize] / [useTabs] / [blankLinesToKeep]) and ignores the inline-spacing and
 * brace-placement knobs (it never moves braces or rewrites inner spacing). [styleId] names the preset for
 * backends that special-case a known profile; an unknown id is treated like "custom".
 */
data class FormatStyle(
    val styleId: String = "google",
    val indentSize: Int = 2,
    val continuationIndent: Int = 4,
    val tabWidth: Int = 2,
    val useTabs: Boolean = false,
    val maxLineLength: Int = 100,
    /** `{` at the end of the line that opens it (K&R / Google) or on its own next line (Allman). */
    val bracePlacement: BracePlacement = BracePlacement.END_OF_LINE,
    /** A space before control-statement parentheses: `if (`, `for (`, `while (`, `catch (`, … */
    val spaceBeforeControlParen: Boolean = true,
    /** A space just inside parentheses: `( x )` vs `(x)`. */
    val spaceWithinParens: Boolean = false,
    /** A space after a comma in argument / parameter / element lists: `a, b` vs `a,b`. */
    val spaceAfterComma: Boolean = true,
    /** Spaces around binary and assignment operators: `a + b`, `x = 1` vs `a+b`, `x=1`. */
    val spaceAroundOperators: Boolean = true,
    /** A space before a `{` block brace: `) {` vs `){`. */
    val spaceBeforeBrace: Boolean = true,
    /** The maximum run of consecutive blank lines kept (extra blank lines are collapsed to this many). */
    val blankLinesToKeep: Int = 1,

    // ---- wrapping & line breaks (Java only) ----
    /** How a long method/constructor parameter list wraps. */
    val wrapMethodParameters: WrapPolicy = WrapPolicy.IF_LONG,
    /** How a long method-call argument list wraps. */
    val wrapMethodArguments: WrapPolicy = WrapPolicy.IF_LONG,
    /** How a long chain of `.method()` calls wraps. */
    val wrapChainedCalls: WrapPolicy = WrapPolicy.IF_LONG,
    /** How a long binary expression (`a + b + c …`) wraps. */
    val wrapBinaryExpressions: WrapPolicy = WrapPolicy.IF_LONG,

    // ---- blank lines (Java only; the Kotlin re-indenter honors only [blankLinesToKeep]) ----
    /** Blank lines kept after the import block. */
    val blankLinesAfterImports: Int = 1,
    /** Blank lines before a method declaration. */
    val blankLinesBeforeMethod: Int = 1,
    /** Blank lines before a field declaration. */
    val blankLinesBeforeField: Int = 0,
    /** Blank lines before the first member of a type body. */
    val blankLinesBeforeFirstMember: Int = 0,
    /** Blank lines between top-level type declarations. */
    val blankLinesBetweenTypes: Int = 1,

    // ---- more spacing (Java only) ----
    /** A space before a statement-terminating `;`. */
    val spaceBeforeSemicolon: Boolean = false,
    /** Spaces around a lambda `->`. */
    val spaceAroundLambdaArrow: Boolean = true,
    /** Spaces around the ternary `?` and `:`. */
    val spaceAroundTernary: Boolean = true,
    /** A space after a type cast `(Foo) x`. */
    val spaceAfterTypeCast: Boolean = true,

    // ---- comments (Java only) ----
    /** Reformat Javadoc / block / line comments (indent, asterisks). */
    val formatComments: Boolean = true,
    /** Wrap comment text at the line width. */
    val wrapComments: Boolean = false,
) {
    /** Indentation unit: a tab when [useTabs], else [indentSize] spaces. */
    fun indentUnit(): String = if (useTabs) "\t" else " ".repeat(indentSize.coerceAtLeast(1))

    companion object {
        // Google and Android share every inline-spacing/brace rule; they differ only in indentation width.
        val GOOGLE = FormatStyle(styleId = "google", indentSize = 2, continuationIndent = 4, tabWidth = 2)
        val ANDROID = FormatStyle(styleId = "android", indentSize = 4, continuationIndent = 8, tabWidth = 4)
    }
}
