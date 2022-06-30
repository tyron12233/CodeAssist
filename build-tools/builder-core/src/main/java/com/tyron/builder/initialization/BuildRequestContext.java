package com.tyron.builder.initialization;

import com.tyron.builder.initialization.BuildCancellationToken;

/**
 * Provides access to services provided by build requester.
 */
public interface BuildRequestContext extends BuildRequestMetaData {
    /**
     * Returns the cancellation token through which the requester can cancel the build.
     */
    BuildCancellationToken getCancellationToken();

    /**
     * Returns an event consumer that will forward events to the build requester.
     */
    BuildEventConsumer getEventConsumer();
}