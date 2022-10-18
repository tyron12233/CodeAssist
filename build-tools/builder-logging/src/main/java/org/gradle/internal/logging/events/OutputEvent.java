package org.gradle.internal.logging.events;

import org.gradle.api.logging.LogLevel;

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