package com.tyron.builder.internal.logging.events;

import com.tyron.builder.api.logging.LogLevel;

import org.jetbrains.annotations.Nullable;

/**
 * Represents some event which may generate output. All implementations are immutable.
 */
//@UsedByScanPlugin
public abstract class OutputEvent {
    /**
     * Returns the log level for this event.
     */
    @Nullable
    public abstract LogLevel getLogLevel();
}