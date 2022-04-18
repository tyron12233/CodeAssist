package com.tyron.builder.internal.logging.config;

import com.tyron.builder.api.logging.LogLevel;

/**
 * Represents a logging system that can generate logging events.
 */
public interface LoggingSourceSystem extends LoggingSystem {
    /**
     * Sets the minimum log level for this logging system. This is advisory only, the logging system may generate events at lower priority, but these will be discarded.
     * Logging systems that have no intrinsic levels should generate events at the specified logging level.
     *
     * <p>This method should not have any effect when capture is not enabled for this logging system using {@link #startCapture()}.</p>
     *
     * @param logLevel The minimum log level.
     * @return the state of this logging system immediately before the changes are applied.
     */
    Snapshot setLevel(LogLevel logLevel);

    /**
     * Enables generation of logging events from this logging system. This method is always called after {@link #setLevel(LogLevel)}.
     *
     * @return the state of this logging system immediately before the changes are applied.
     */
    Snapshot startCapture();
}
