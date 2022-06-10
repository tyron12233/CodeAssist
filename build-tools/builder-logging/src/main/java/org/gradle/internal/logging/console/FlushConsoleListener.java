package org.gradle.internal.logging.console;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.UpdateNowEvent;

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
