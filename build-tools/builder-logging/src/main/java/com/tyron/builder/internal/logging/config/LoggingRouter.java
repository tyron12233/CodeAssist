package com.tyron.builder.internal.logging.config;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.LoggingOutputInternal;

public interface LoggingRouter extends LoggingSystem, LoggingOutputInternal {
    void configure(LogLevel logLevel);

    void enableUserStandardOutputListeners();
}
