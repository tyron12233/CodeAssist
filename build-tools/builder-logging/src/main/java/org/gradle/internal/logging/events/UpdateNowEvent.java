package org.gradle.internal.logging.events;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.api.logging.LogLevel;

/**
 * Indicates an event that can trigger an immediate update to the console.
 */
public class UpdateNowEvent extends OutputEvent {

    private final long timestamp;

    public UpdateNowEvent(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return UpdateNowEvent.class.getSimpleName() + " " + timestamp;
    }

    @Override
    public LogLevel getLogLevel() {
        return null;
    }
}