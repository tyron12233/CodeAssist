package org.gradle.internal.logging.config;

import org.gradle.api.logging.LogLevel;

public interface LoggingConfigurer {
    void configure(LogLevel logLevel);
}
