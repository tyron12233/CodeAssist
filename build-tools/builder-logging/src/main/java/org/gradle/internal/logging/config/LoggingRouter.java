package org.gradle.internal.logging.config;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.LoggingOutputInternal;

public interface LoggingRouter extends LoggingSystem, LoggingOutputInternal {
    void configure(LogLevel logLevel);

    void enableUserStandardOutputListeners();
}
