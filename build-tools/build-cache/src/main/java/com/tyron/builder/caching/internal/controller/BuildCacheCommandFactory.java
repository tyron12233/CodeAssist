package com.tyron.builder.caching.internal.controller;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.internal.CacheableEntity;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

import java.time.Duration;
import java.util.Map;

public interface BuildCacheCommandFactory {
    BuildCacheLoadCommand<LoadMetadata> createLoad(BuildCacheKey cacheKey, CacheableEntity entity);

    BuildCacheStoreCommand createStore(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, ? extends FileSystemSnapshot> snapshots, Duration executionTime);

    interface LoadMetadata {
        OriginMetadata getOriginMetadata();
        ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots();
    }
}
