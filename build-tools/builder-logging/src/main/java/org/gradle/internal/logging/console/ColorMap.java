package org.gradle.internal.logging.console;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.Style;

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
