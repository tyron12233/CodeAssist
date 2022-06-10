package org.gradle.caching.internal.controller.operations;

import org.gradle.caching.BuildCacheKey;

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