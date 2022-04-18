package com.tyron.builder.api.logging.configuration;

import com.tyron.builder.api.logging.LogLevel;

/**
 * A {@code LoggingConfiguration} defines the logging settings for a Gradle build.
 */
public interface LoggingConfiguration {
    /**
     * Returns the minimum logging level to use. All log messages with a lower log level are ignored.
     * Defaults to {@link LogLevel#LIFECYCLE}.
     */
    LogLevel getLogLevel();

    /**
     * Specifies the minimum logging level to use. All log messages with a lower log level are ignored.
     */
    void setLogLevel(LogLevel logLevel);

    /**
     * Returns the style of logging output that should be written to the console.
     * Defaults to {@link ConsoleOutput#Auto}
     */
    ConsoleOutput getConsoleOutput();

    /**
     * Specifies the style of logging output that should be written to the console.
     */
    void setConsoleOutput(ConsoleOutput consoleOutput);

    /**
     * Specifies which type of warnings should be written to the console.
     * @since 4.5
     */
    WarningMode getWarningMode();

    /**
     * Specifies which type of warnings should be written to the console.
     * @since 4.5
     */
    void setWarningMode(WarningMode warningMode);

    /**
     * Returns the detail that should be included in stacktraces. Defaults to {@link ShowStacktrace#INTERNAL_EXCEPTIONS}.
     */
    ShowStacktrace getShowStacktrace();

    /**
     * Sets the detail that should be included in stacktraces.
     */
    void setShowStacktrace(ShowStacktrace showStacktrace);
}