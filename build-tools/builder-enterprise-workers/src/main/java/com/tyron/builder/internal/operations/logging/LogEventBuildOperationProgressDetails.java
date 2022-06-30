package com.tyron.builder.internal.operations.logging;

/**
 * Build operation observer's view of {@code org.gradle.internal.logging.events.LogEvent}.
 *
 * @since 7.4
 */
public interface LogEventBuildOperationProgressDetails {
    String getMessage();

    Throwable getThrowable();

    String getCategory();

    LogEventLevel getLevel();
}
