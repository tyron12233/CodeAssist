package com.tyron.builder.internal.logging.config;

import com.tyron.builder.api.logging.LogLevel;

public interface LoggingConfigurer {
    void configure(LogLevel logLevel);
}
