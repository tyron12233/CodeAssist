package com.tyron.builder.internal.operations.logging;

/**
 * Build operation observer's view of {@code org.gradle.internal.logging.events.ProgressStartEvent}.
 *
 * See LoggingBuildOperationProgressBroadcaster.
 *
 * @since 7.4
 */
public interface ProgressStartBuildOperationProgressDetails {

    String getDescription();

    String getCategory();

    LogEventLevel getLevel();

    /**
     * While this may be null on the underlying implementation,
     * objects with a null value for this will not be forwarded as build operation progress.
     * Therefore, when observing as build operation progress this is never null.
     */
    String getLoggingHeader();

}
