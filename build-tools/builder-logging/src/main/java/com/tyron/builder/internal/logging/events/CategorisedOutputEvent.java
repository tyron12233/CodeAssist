package com.tyron.builder.internal.logging.events;

import com.tyron.builder.api.logging.LogLevel;

public class CategorisedOutputEvent extends OutputEvent {
    private final String category;
    private final LogLevel logLevel;
    private final long timestamp;

    public CategorisedOutputEvent(long timestamp, String category, LogLevel logLevel) {
        this.timestamp = timestamp;
        this.category = category;
        this.logLevel = logLevel;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public LogLevel getLogLevel() {
        return logLevel;
    }

    public String getCategory() {
        return category;
    }
}