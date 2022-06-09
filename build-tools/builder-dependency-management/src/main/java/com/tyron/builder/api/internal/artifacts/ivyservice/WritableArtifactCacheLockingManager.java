/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.internal.artifacts.ivyservice;

import com.tyron.builder.api.internal.filestore.DefaultArtifactIdentifierFileStore;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.CleanupAction;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.internal.CompositeCleanupAction;
import com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup;
import com.tyron.builder.cache.internal.SingleDepthFilesFinder;
import com.tyron.builder.cache.internal.UnusedVersionsCacheCleanup;
import com.tyron.builder.cache.internal.UsedGradleVersions;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.resource.cached.DefaultExternalResourceFileStore;
import com.tyron.builder.internal.serialize.Serializer;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.function.Function;

import static com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_EXTERNAL_CACHE_ENTRIES;
import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

public class WritableArtifactCacheLockingManager implements ArtifactCacheLockingManager, Closeable {
    private final PersistentCache cache;

    public WritableArtifactCacheLockingManager(CacheRepository cacheRepository,
                                               ArtifactCacheMetadata cacheMetaData,
                                               FileAccessTimeJournal fileAccessTimeJournal,
                                               UsedGradleVersions usedGradleVersions) {
        cache = cacheRepository
                .cache(cacheMetaData.getCacheDir())
                .withCrossVersionCache(CacheBuilder.LockTarget.CacheDirectory)
                .withDisplayName("artifact cache")
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Don't need to lock anything until we use the caches
                .withCleanup(createCleanupAction(cacheMetaData, fileAccessTimeJournal, usedGradleVersions))
                .open();
    }

    private CleanupAction createCleanupAction(ArtifactCacheMetadata cacheMetaData, FileAccessTimeJournal fileAccessTimeJournal, UsedGradleVersions usedGradleVersions) {
        long maxAgeInDays = Long.getLong("com.tyron.builder.internal.cleanup.external.max.age", DEFAULT_MAX_AGE_IN_DAYS_FOR_EXTERNAL_CACHE_ENTRIES);
        return CompositeCleanupAction.builder()
                .add(UnusedVersionsCacheCleanup.create(CacheLayout.ROOT.getName(), CacheLayout.ROOT.getVersionMapping(), usedGradleVersions))
                .add(cacheMetaData.getExternalResourcesStoreDirectory(),
                    UnusedVersionsCacheCleanup.create(CacheLayout.RESOURCES.getName(), CacheLayout.RESOURCES.getVersionMapping(), usedGradleVersions),
                    new LeastRecentlyUsedCacheCleanup(new SingleDepthFilesFinder(DefaultExternalResourceFileStore.FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP), fileAccessTimeJournal, maxAgeInDays))
                .add(cacheMetaData.getFileStoreDirectory(),
                    UnusedVersionsCacheCleanup.create(CacheLayout.FILE_STORE.getName(), CacheLayout.FILE_STORE.getVersionMapping(), usedGradleVersions),
                    new LeastRecentlyUsedCacheCleanup(new SingleDepthFilesFinder(DefaultArtifactIdentifierFileStore.FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP), fileAccessTimeJournal, maxAgeInDays))
                .add(cacheMetaData.getMetaDataStoreDirectory().getParentFile(),
                    UnusedVersionsCacheCleanup.create(CacheLayout.META_DATA.getName(), CacheLayout.META_DATA.getVersionMapping(), usedGradleVersions))
                .build();
    }

    @Override
    public void close() {
        cache.close();
    }

    @Override
    public <T> T withFileLock(Factory<? extends T> action) {
        return cache.withFileLock(action);
    }

    @Override
    public void withFileLock(Runnable action) {
        cache.withFileLock(action);
    }

    @Override
    public <T> T useCache(Factory<? extends T> action) {
        return cache.useCache(action);
    }

    @Override
    public void useCache(Runnable action) {
        cache.useCache(action);
    }

    @Override
    public <K, V> PersistentIndexedCache<K, V> createCache(String cacheName, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        String cacheFileInMetaDataStore = CacheLayout.META_DATA.getKey() + "/" + cacheName;
        final PersistentIndexedCache<K, V> persistentCache = cache.createCache(PersistentIndexedCacheParameters.of(cacheFileInMetaDataStore, keySerializer, valueSerializer));
        return new CacheLockingPersistentCache<>(persistentCache);
    }

    private class CacheLockingPersistentCache<K, V> implements PersistentIndexedCache<K, V> {
        private final PersistentIndexedCache<K, V> persistentCache;

        public CacheLockingPersistentCache(PersistentIndexedCache<K, V> persistentCache) {
            this.persistentCache = persistentCache;
        }

        @Nullable
        @Override
        public V getIfPresent(final K key) {
            return cache.useCache(() -> persistentCache.getIfPresent(key));
        }

        @Override
        public V get(final K key, final Function<? super K, ? extends V> producer) {
            return cache.useCache(() -> persistentCache.get(key, producer));
        }

        @Override
        public void put(final K key, final V value) {
            cache.useCache(() -> persistentCache.put(key, value));
        }

        @Override
        public void remove(final K key) {
            cache.useCache(() -> persistentCache.remove(key));
        }
    }
}
