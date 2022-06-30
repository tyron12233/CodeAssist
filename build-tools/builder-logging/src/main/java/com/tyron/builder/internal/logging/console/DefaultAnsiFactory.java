package com.tyron.builder.internal.logging.console;

import org.fusesource.jansi.Ansi;

public class DefaultAnsiFactory implements AnsiFactory {
    private final boolean forceAnsi;

    public DefaultAnsiFactory(boolean forceAnsi) {
        this.forceAnsi = forceAnsi;
    }

    @Override
    public Ansi create() {
        if (forceAnsi) {
            return new Ansi();
        } else {
            return Ansi.ansi();
        }
    }
}
