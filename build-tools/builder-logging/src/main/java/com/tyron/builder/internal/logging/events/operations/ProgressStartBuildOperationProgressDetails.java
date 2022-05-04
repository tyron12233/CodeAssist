package com.tyron.builder.internal.logging.events.operations;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;

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
public interface ProgressStartBuildOperationProgressDetails extends com.tyron.builder.internal.operations.logging.ProgressStartBuildOperationProgressDetails {
    LogLevel getLogLevel();
}
