package com.tyron.builder.caching.internal.controller.operations;

import com.tyron.builder.caching.BuildCacheKey;

public class PackOperationDetails implements BuildCacheArchivePackBuildOperationType.Details {

    private final BuildCacheKey key;

    public PackOperationDetails(BuildCacheKey key) {
        this.key = key;
    }

    @Override
    public String getCacheKey() {
        return key.getHashCode();
    }

}