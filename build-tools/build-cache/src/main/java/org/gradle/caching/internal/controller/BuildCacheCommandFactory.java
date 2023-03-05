package org.gradle.caching.internal.controller;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.internal.snapshot.FileSystemSnapshot;

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
