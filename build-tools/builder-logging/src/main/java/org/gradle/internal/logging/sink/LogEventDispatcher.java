package org.gradle.internal.logging.sink;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.api.logging.LogLevel;

import javax.annotation.Nullable;

public class LogEventDispatcher implements OutputEventListener {
    private final OutputEventListener stdoutChain;
    private final OutputEventListener stderrChain;

    public LogEventDispatcher(@Nullable OutputEventListener stdoutChain, @Nullable OutputEventListener stderrChain) {
        this.stdoutChain = stdoutChain;
        this.stderrChain = stderrChain;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event.getLogLevel() == null) {
            dispatch(event, stdoutChain);
            dispatch(event, stderrChain);
        } else if (event.getLogLevel() == LogLevel.ERROR) {
            dispatch(event, stderrChain);
        } else {
            dispatch(event, stdoutChain);
        }
    }

    protected void dispatch(OutputEvent event, OutputEventListener listener) {
        if (listener != null) {
            listener.onOutput(event);
        }
    }
}
