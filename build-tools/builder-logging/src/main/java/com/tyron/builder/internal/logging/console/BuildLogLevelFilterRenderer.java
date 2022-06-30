package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;


public class BuildLogLevelFilterRenderer implements OutputEventListener {
    private final OutputEventListener listener;
    private LogLevel logLevel = LogLevel.LIFECYCLE;

    public BuildLogLevelFilterRenderer(OutputEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event.getLogLevel() != null && event.getLogLevel().compareTo(logLevel) < 0) {
            return;
        }
        if (event instanceof LogLevelChangeEvent) {
            LogLevelChangeEvent changeEvent = (LogLevelChangeEvent) event;
            logLevel = changeEvent.getNewLogLevel();
        }
        listener.onOutput(event);
    }
}
