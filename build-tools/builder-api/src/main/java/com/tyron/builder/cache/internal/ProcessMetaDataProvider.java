package com.tyron.builder.cache.internal;

/**
 * Provides meta-data about the current process. Generally used for logging and error messages.
 */
public interface ProcessMetaDataProvider {
    /**
     * Returns a unique identifier for this process. Should be unique across all processes on the local machine.
     */
    String getProcessIdentifier();

    /**
     * Returns a display name for this process. Should allow a human to figure out which process the display name refers to.
     */
    String getProcessDisplayName();
}