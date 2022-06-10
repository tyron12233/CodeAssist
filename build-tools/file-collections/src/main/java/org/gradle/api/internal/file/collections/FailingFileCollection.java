package org.gradle.api.internal.file.collections;

import org.gradle.api.file.FileCollection;

/**
 * A {@link FileCollection} implementation that throws a given exception when visited.
 */
public class FailingFileCollection extends LazilyInitializedFileCollection {

    private final String displayName;
    private final RuntimeException failure;

    public FailingFileCollection(String displayName, RuntimeException failure) {
        this.displayName = displayName;
        this.failure = failure;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public FileCollection createDelegate() {
        throw failure;
    }
}
