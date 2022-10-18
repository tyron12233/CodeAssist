package org.gradle.caching.internal.controller.operations;


import org.gradle.internal.operations.BuildOperationType;

public final class BuildCacheArchivePackBuildOperationType implements BuildOperationType<BuildCacheArchivePackBuildOperationType.Details, BuildCacheArchivePackBuildOperationType.Result> {

    public interface Details {

        /**
         * The cache key.
         */
        String getCacheKey();

    }

    public interface Result {

        long getArchiveSize();

        long getArchiveEntryCount();

    }

}