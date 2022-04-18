package com.tyron.builder.internal.file;

import java.io.File;

/**
 * Tracks access to files.
 */
public interface FileAccessTracker {
    /**
     * Marks the supplied file as accessed.
     *
     * If the supplied file is unknown to this tracker, implementations must
     * simply ignore it instead of throwing an exception. However, depending
     * on the use case, implementations may throw an exception when marking a
     * known file fails.
     */
    void markAccessed(File file);
}