package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.api.logging.LogLevel;

/**
 * Notifies output consumers that the log level has changed. Consumers will not receive any further events at a lesser log level.
 */
public class LogLevelChangeEvent extends OutputEvent {
    private final LogLevel newLogLevel;

    public LogLevelChangeEvent(LogLevel newLogLevel) {
        this.newLogLevel = newLogLevel;
    }

    public LogLevel getNewLogLevel() {
        return newLogLevel;
    }

    @Override
    public String toString() {
        return LogLevelChangeEvent.class.getSimpleName() + " " + newLogLevel;
    }

    @Override
    public LogLevel getLogLevel() {
        return null;
    }
}
