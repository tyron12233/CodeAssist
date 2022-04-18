package com.tyron.builder.caching.internal.controller.operations;

import com.tyron.builder.caching.BuildCacheKey;


public class UnpackOperationDetails implements BuildCacheArchiveUnpackBuildOperationType.Details {

    private final BuildCacheKey key;
    private final long archiveSize;

    public UnpackOperationDetails(BuildCacheKey key, long archiveSize) {
        this.key = key;
        this.archiveSize = archiveSize;
    }

    @Override
    public String getCacheKey() {
        return key.getHashCode();
    }

    @Override
    public long getArchiveSize() {
        return archiveSize;
    }
}
