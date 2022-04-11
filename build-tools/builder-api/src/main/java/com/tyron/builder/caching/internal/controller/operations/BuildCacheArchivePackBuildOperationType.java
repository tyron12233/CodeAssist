package com.tyron.builder.caching.internal.controller.operations;


import com.tyron.builder.api.internal.operations.BuildOperationType;

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