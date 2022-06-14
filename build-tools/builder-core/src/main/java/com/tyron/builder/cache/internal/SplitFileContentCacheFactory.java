package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.GlobalCacheLocations;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.serialize.Serializer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * A {@link FileContentCacheFactory} that delegates to the global cache for files that are known to be immutable and shared between different builds.
 * All other requests are delegated to the build-local cache. Closing this factory will only close the local delegate, not the global one.
 */
public class SplitFileContentCacheFactory implements FileContentCacheFactory, Closeable {
    private final FileContentCacheFactory globalFactory;
    private final FileContentCacheFactory localFactory;
    private final GlobalCacheLocations globalCacheLocations;

    public SplitFileContentCacheFactory(FileContentCacheFactory globalFactory, FileContentCacheFactory localFactory, GlobalCacheLocations globalCacheLocations) {
        this.globalFactory = globalFactory;
        this.localFactory = localFactory;
        this.globalCacheLocations = globalCacheLocations;
    }

    @Override
    public void close() throws IOException {
        CompositeStoppable.stoppable(localFactory).stop();
    }

    @Override
    public <V> FileContentCache<V> newCache(String name, int normalizedCacheSize, Calculator<? extends V> calculator, Serializer<V> serializer) {
        FileContentCache<V> globalCache = globalFactory.newCache(name, normalizedCacheSize, calculator, serializer);
        FileContentCache<V> localCache = localFactory.newCache(name, normalizedCacheSize, calculator, serializer);
        return new SplitFileContentCache<>(globalCache, localCache, globalCacheLocations);
    }

    private static final class SplitFileContentCache<V> implements FileContentCache<V> {
        private final FileContentCache<V> globalCache;
        private final FileContentCache<V> localCache;
        private final GlobalCacheLocations globalCacheLocations;

        private SplitFileContentCache(FileContentCache<V> globalCache, FileContentCache<V> localCache, GlobalCacheLocations globalCacheLocations) {
            this.globalCache = globalCache;
            this.localCache = localCache;
            this.globalCacheLocations = globalCacheLocations;
        }

        @Override
        public V get(File file) {
            if (globalCacheLocations.isInsideGlobalCache(file.getPath())) {
                return globalCache.get(file);
            } else {
                return localCache.get(file);
            }
        }
    }
}
