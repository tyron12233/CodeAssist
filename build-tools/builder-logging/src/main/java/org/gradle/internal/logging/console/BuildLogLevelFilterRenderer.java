package org.gradle.internal.logging.console;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.LogLevelChangeEvent;


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
