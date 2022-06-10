package org.gradle.caching.internal.controller.operations;


import org.gradle.internal.operations.BuildOperationType;

public final class BuildCacheArchiveUnpackBuildOperationType implements BuildOperationType<BuildCacheArchiveUnpackBuildOperationType.Details, BuildCacheArchiveUnpackBuildOperationType.Result> {

    public interface Details {

        /**
         * The cache key.
         */
        String getCacheKey();

        long getArchiveSize();

    }

    public interface Result {

        long getArchiveEntryCount();

    }

}