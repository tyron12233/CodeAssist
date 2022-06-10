package org.gradle.internal.logging.events.operations;

import org.gradle.api.logging.LogLevel;

/**
 * Build operation observer's view of {@link LogEvent}.
 *
 * See LoggingBuildOperationProgressBroadcaster.
 *
 * @since 4.7
 */
//@UsedByScanPlugin("Non-internal replacement available since Gradle 7.4")
@Deprecated
@SuppressWarnings("DeprecatedIsStillUsed")
public interface LogEventBuildOperationProgressDetails extends org.gradle.internal.operations.logging.LogEventBuildOperationProgressDetails {
    /**
     * Replaced by {@link #getLevel()}.
     */
    LogLevel getLogLevel();
}
