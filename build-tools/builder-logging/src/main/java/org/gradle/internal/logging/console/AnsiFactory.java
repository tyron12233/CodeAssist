package org.gradle.internal.logging.console;

import org.fusesource.jansi.Ansi;

public interface AnsiFactory {
    Ansi create();
}
