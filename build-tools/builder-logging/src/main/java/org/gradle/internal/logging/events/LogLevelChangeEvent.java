package org.gradle.internal.logging.events;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.api.logging.LogLevel;

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
