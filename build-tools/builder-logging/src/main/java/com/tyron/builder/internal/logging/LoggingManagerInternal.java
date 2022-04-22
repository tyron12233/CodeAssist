package com.tyron.builder.internal.logging;

import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.api.logging.LoggingOutput;
import com.tyron.builder.api.logging.StandardOutputListener;

/**
 * Provides access to and control over the logging system. Log manager represents some 'scope', and log managers can be nested in a stack.
 */
public interface LoggingManagerInternal extends LoggingManager, StandardOutputCapture, LoggingOutputInternal {
    /**
     * Makes this log manager active, replacing the currently active log manager, if any. Applies any settings defined by this log manager. Initialises the logging system when there is no log manager currently active.
     *
     * <p>While a log manager is active, any changes made to the settings will take effect immediately. When a log manager is not active, changes to its settings will apply only once it is made active by calling {@link #start()}.</p>
     */
    @Override
    LoggingManagerInternal start();

    /**
     * Stops logging, restoring the log manger that was active when {@link #start()} was called on this manager. Shuts down the logging system when there was no log manager active prior to starting this one.
     */
    @Override
    LoggingManagerInternal stop();

    /**
     * Consumes logging from System.out and System.err and Java util logging.
     */
    LoggingManagerInternal captureSystemSources();

    /**
     * Sets the log level to capture stdout at. Does not enable capture.
     */
    @Override
    LoggingManagerInternal captureStandardOutput(LogLevel level);

    /**
     * Sets the log level to capture stderr at. Does not enable capture.
     */
    @Override
    LoggingManagerInternal captureStandardError(LogLevel level);

    LoggingManagerInternal setLevelInternal(LogLevel logLevel);

    /**
     * Allows {@link LoggingOutput#addStandardOutputListener(StandardOutputListener)} and {@link LoggingOutput#addStandardErrorListener(StandardOutputListener)} to be used.
     *
     * <p>This should be used only when custom user listeners are required, i.e. only in the build JVM around the build execution.
     */
    LoggingManagerInternal enableUserStandardOutputListeners();
}
