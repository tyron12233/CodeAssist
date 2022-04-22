package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.api.logging.LogLevel;

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