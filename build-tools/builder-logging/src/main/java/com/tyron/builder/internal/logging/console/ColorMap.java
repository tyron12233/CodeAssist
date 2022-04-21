package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.text.Style;

import org.fusesource.jansi.Ansi;

public interface ColorMap {
    Color getColourFor(StyledTextOutput.Style style);

    Color getColourFor(Style style);

    Color getStatusBarColor();

    interface Color {
        void on(Ansi ansi);

        void off(Ansi ansi);
    }
}
