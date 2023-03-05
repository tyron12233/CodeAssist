package org.gradle.internal.logging.events.operations;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.ProgressStartEvent;

/**
 * Build operation observer's view of {@link ProgressStartEvent}.
 *
 * See LoggingBuildOperationProgressBroadcaster.
 *
 * @since 4.7
 */
//@UsedByScanPlugin("Non-internal replacement available since Gradle 7.4")
@Deprecated
@SuppressWarnings("DeprecatedIsStillUsed")
public interface ProgressStartBuildOperationProgressDetails extends org.gradle.internal.operations.logging.ProgressStartBuildOperationProgressDetails {
    LogLevel getLogLevel();
}
