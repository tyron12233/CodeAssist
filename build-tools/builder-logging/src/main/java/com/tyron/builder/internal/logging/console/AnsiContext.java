package com.tyron.builder.internal.logging.console;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.text.Style;

public interface AnsiContext {
    /**
     * Change the ANSI color before executing the specified action. The color is reverted back after the action is executed.
     *
     * @param color the color to use
     * @param action the action to execute on ANSI with the specified color
     * @return the current context
     */
    AnsiContext withColor(ColorMap.Color color, Action<? super AnsiContext> action);

    /**
     * Change the ANSI style before executing the specified action. The style is reverted back after the action is executed.
     *
     * @param style the style to use
     * @param action the action to execute on ANSI with the specified style
     * @return the current context
     */
    AnsiContext withStyle(Style style, Action<? super AnsiContext> action);

    /**
     * Change the ANSI style before executing the specified action. The style is reverted back after the action is executed.
     *
     * @param style the style to use
     * @param action the action to execute on ANSI with the specified style
     * @return the current context
     */
    AnsiContext withStyle(StyledTextOutput.Style style, Action<? super AnsiContext> action);

    /**
     * @return the current context with the specified text written.
     */
    AnsiContext a(CharSequence value);

    /**
     * @return the current context with a new line written.
     */
    AnsiContext newLine();

    /**
     * @return the current context with the specified new line written.
     */
    AnsiContext newLines(int numberOfNewLines);

    /**
     * @return the current context with the characters moving forward from the write position erased.
     */
    AnsiContext eraseForward();

    /**
     * @return the current context with the entire line erased.
     */
    AnsiContext eraseAll();

    /**
     * @return the current context moved to the specified position.
     */
    AnsiContext cursorAt(Cursor cursor);

    /**
     * @return a new context at the specified write position.
     */
    AnsiContext writeAt(Cursor writePos);
}
