package com.tyron.builder.internal.logging.console;

/**
 * This label have the concept of been drawn on screen.
 */
public interface RedrawableLabel extends Label, StyledLabel {
    void redraw(AnsiContext ansi);
}

