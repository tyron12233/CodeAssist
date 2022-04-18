package com.tyron.builder.api.logging;

/**
 * <p>A {@code LoggingManager} provides access to and control over the Gradle logging system. Using this interface, you
 * can control standard output and error capture and receive logging events.</p>
 */
public interface LoggingManager extends LoggingOutput {
    /**
     * Requests that output written to System.out be routed to Gradle's logging system. The default is that System.out
     * is routed to {@link LogLevel#QUIET}
     *
     * @param level The log level to route System.out to.
     * @return this
     */
    LoggingManager captureStandardOutput(LogLevel level);

    /**
     * Requests that output written to System.err be routed to Gradle's logging system. The default is that System.err
     * is routed to {@link LogLevel#ERROR}.
     *
     * @param level The log level to route System.err to.
     * @return this
     */
    LoggingManager captureStandardError(LogLevel level);

    /**
     * Returns the log level that output written to System.out will be mapped to.
     *
     * @return The log level. Returns null when standard output capture is disabled.
     */
    LogLevel getStandardOutputCaptureLevel();

    /**
     * Returns the log level that output written to System.err will be mapped to.
     *
     * @return The log level. Returns null when standard error capture is disabled.
     */
    LogLevel getStandardErrorCaptureLevel();

    /**
     * Returns the current logging level.
     *
     * @return The current logging level.
     */
    LogLevel getLevel();
}
