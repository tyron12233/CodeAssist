package org.gradle.api.internal.changedetection.state;


import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.PersistentIndexedCacheParameters;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.scopes.ScopedCache;

import java.io.Closeable;

public class CrossBuildFileHashCache implements Closeable {

    private final PersistentCache cache;
    private final InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory;

    public CrossBuildFileHashCache(ScopedCache scopedCache, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, Kind cacheKind) {
        this.inMemoryCacheDecoratorFactory = inMemoryCacheDecoratorFactory;
        cache = scopedCache.cache(cacheKind.cacheId)
                .withDisplayName(cacheKind.description)
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Lock on demand
                .open();
    }

    public <K, V> PersistentIndexedCache<K, V> createCache(PersistentIndexedCacheParameters<K, V> parameters, int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses) {
        return cache.createCache(parameters
                .withCacheDecorator(inMemoryCacheDecoratorFactory.decorator(maxEntriesToKeepInMemory, cacheInMemoryForShortLivedProcesses))
        );
    }

    @Override
    public void close() {
        cache.close();
    }

    public enum Kind {
        FILE_HASHES("fileHashes", "file hash cache"),
        CHECKSUMS("checksums", "checksums cache");
        private final String cacheId;
        private final String description;

        Kind(String cacheId, String description) {
            this.cacheId = cacheId;
            this.description = description;
        }

        public String getCacheId() {
            return cacheId;
        }

        public String getDescription() {
            return description;
        }
    }
}