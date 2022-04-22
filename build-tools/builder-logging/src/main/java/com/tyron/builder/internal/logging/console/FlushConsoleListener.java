package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.EndOutputEvent;
import com.tyron.builder.internal.logging.events.UpdateNowEvent;

/**
 * Flushes the console output. Used when no other listener in the chain flushes the console.
 */
public class FlushConsoleListener implements OutputEventListener {
    private final OutputEventListener delegate;
    private final Console console;

    public FlushConsoleListener(Console console, OutputEventListener delegate) {
        this.delegate = delegate;
        this.console = console;
    }

    @Override
    public void onOutput(OutputEvent event) {
        delegate.onOutput(event);
        if (event instanceof UpdateNowEvent || event instanceof EndOutputEvent) {
            console.flush();
        }
    }
}
