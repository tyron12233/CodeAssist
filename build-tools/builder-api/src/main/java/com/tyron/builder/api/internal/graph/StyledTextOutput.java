package com.tyron.builder.api.internal.graph;

/**
 * Provides streaming of styled text, that is, a stream of text with inline styling information. Implementations are not
 * required to be thread-safe.
 */
public interface StyledTextOutput extends Appendable {
    enum Style {
        /**
         * Regular text.
         */
        Normal,
        /**
         * A header.
         */
        Header,
        /**
         * User input
         */
        UserInput,
        /**
         * An identifier for something
         */
        Identifier,
        /**
         * The description of something
         */
        Description,
        /**
         * Operation progress status
         */
        ProgressStatus,
        /**
         * General purpose success message
         */
        Success,
        /**
         * <b>Emphasized</b> success message
         */
        SuccessHeader,
        /**
         * General purpose failure message
         */
        Failure,
        /**
         * <b>Emphasized</b> failure message
         */
        FailureHeader,
        /**
         * General purpose informational text
         */
        Info,
        /**
         * General purpose error text
         */
        Error
    }

    /**
     * Appends a character using the current style.
     *
     * @param c The character
     * @return this
     */
    @Override
    StyledTextOutput append(char c);

    /**
     * Appends a sequence of characters using the current style.
     *
     * @param csq The character sequence
     * @return this.
     */
    @Override
    StyledTextOutput append(CharSequence csq);

    /**
     * Appends a sequence of characters using the current style.
     *
     * @param csq The character sequence
     * @return this.
     */
    @Override
    StyledTextOutput append(CharSequence csq, int start, int end);

    /**
     * Switches to a new style. The default style is {@link Style#Normal}.
     *
     * @param style The style.
     * @return this
     */
    StyledTextOutput style(Style style);

    /**
     * Creates a copy of this output which uses the given style. This can be used to generate text in a different style
     * and then return to the current style. For example:
     * <pre>
     * output.style(Info)
     * output.withStyle(Description).format("%s %s", name, description) // output in Description style
     * output.println(" text") // output in Info style
     * </pre>
     *
     * @param style The temporary style
     * @return the copy
     */
    StyledTextOutput withStyle(Style style);

    /**
     * Appends text using the current style.
     *
     * @param text The text
     * @return this
     */
    StyledTextOutput text(Object text);

    /**
     * Appends text using the current style and starts a new line.
     *
     * @param text The text
     * @return this
     */
    StyledTextOutput println(Object text);

    /**
     * Appends a formatted string using the current style.
     *
     * @param pattern The pattern string
     * @param args    The args for the pattern
     * @return this
     */
    StyledTextOutput format(String pattern, Object... args);

    /**
     * Appends a formatted string using the current style and starts a new line.
     *
     * @param pattern The pattern string
     * @param args    The args for the pattern
     * @return this
     */
    StyledTextOutput formatln(String pattern, Object... args);

    /**
     * Starts a new line.
     *
     * @return this
     */
    StyledTextOutput println();

    /**
     * Appends the stacktrace of the given exception using the current style.
     *
     * @param throwable The exception
     * @return this
     */
    StyledTextOutput exception(Throwable throwable);
}