package com.tyron.builder.caching.internal.controller.operations;


import com.tyron.builder.api.internal.operations.BuildOperationType;

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