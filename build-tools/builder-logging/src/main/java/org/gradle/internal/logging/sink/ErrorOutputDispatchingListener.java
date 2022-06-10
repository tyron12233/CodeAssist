package org.gradle.internal.logging.sink;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.api.logging.LogLevel;

class ErrorOutputDispatchingListener implements OutputEventListener {
    private final OutputEventListener stderrChain;
    private final OutputEventListener stdoutChain;

    public ErrorOutputDispatchingListener(OutputEventListener stderrChain, OutputEventListener stdoutChain) {
        this.stderrChain = stderrChain;
        this.stdoutChain = stdoutChain;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event.getLogLevel() == null) {
            stderrChain.onOutput(event);
            stdoutChain.onOutput(event);
        } else if (event.getLogLevel() != LogLevel.ERROR) {
            stdoutChain.onOutput(event);
        } else {
            // TODO - should attempt to flush the output stream prior to writing to the error stream (and vice versa)
            stderrChain.onOutput(event);
        }
    }
}
