package com.tyron.builder.api.internal.changedetection.state;


import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.ScopedCache;

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