package com.tyron.builder.cache.internal;

import com.google.common.hash.HashCode;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.scopes.ScopedCache;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.serialize.HashCodeSerializer;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.vfs.FileSystemAccess;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

public class DefaultFileContentCacheFactory implements FileContentCacheFactory, Closeable {
    private final ListenerManager listenerManager;
    private final FileSystemAccess fileSystemAccess;
    private final InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory;
    private final PersistentCache cache;
    private final HashCodeSerializer hashCodeSerializer = new HashCodeSerializer();
    private final ConcurrentMap<String, DefaultFileContentCache<?>> caches = new ConcurrentHashMap<>();

    public DefaultFileContentCacheFactory(ListenerManager listenerManager, FileSystemAccess fileSystemAccess, ScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        this.listenerManager = listenerManager;
        this.fileSystemAccess = fileSystemAccess;
        this.inMemoryCacheDecoratorFactory = inMemoryCacheDecoratorFactory;
        cache = cacheRepository
            .cache("fileContent")
            .withDisplayName("file content cache")
            .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Lock on demand
            .open();
    }

    @Override
    public void close() throws IOException {
        cache.close();
    }

    @Override
    public <V> FileContentCache<V> newCache(String name, int normalizedCacheSize, final Calculator<? extends V> calculator, Serializer<V> serializer) {
        PersistentIndexedCacheParameters<HashCode, V> parameters = PersistentIndexedCacheParameters.of(name, hashCodeSerializer, serializer)
            .withCacheDecorator(inMemoryCacheDecoratorFactory.decorator(normalizedCacheSize, true));
        PersistentIndexedCache<HashCode, V> store = cache.createCache(parameters);

        DefaultFileContentCache<V> cache = Cast.uncheckedCast(caches.get(name));
        if (cache == null) {
            cache = new DefaultFileContentCache<>(name, fileSystemAccess, store, calculator);
            DefaultFileContentCache<V> existing = Cast.uncheckedCast(caches.putIfAbsent(name, cache));
            if (existing == null) {
                listenerManager.addListener(cache);
            } else {
                cache = existing;
            }
        }

        cache.assertStoredIn(store);
        return cache;
    }

    /**
     * Maintains 2 levels of in-memory caching. The first, fast, level indexes on file path and contains the value that is very likely to reflect the current contents of the file. This first cache is invalidated whenever any task actions are run.
     *
     * The second level indexes on the hash of file content and contains the value that was calculated from a file with the given hash.
     */
    private static class DefaultFileContentCache<V> implements FileContentCache<V>, OutputChangeListener {
        private final Map<File, V> locationCache = new ConcurrentHashMap<>();
        private final String name;
        private final FileSystemAccess fileSystemAccess;
        private final PersistentIndexedCache<HashCode, V> contentCache;
        private final Calculator<? extends V> calculator;

        DefaultFileContentCache(String name, FileSystemAccess fileSystemAccess, PersistentIndexedCache<HashCode, V> contentCache, Calculator<? extends V> calculator) {
            this.name = name;
            this.fileSystemAccess = fileSystemAccess;
            this.contentCache = contentCache;
            this.calculator = calculator;
        }

        @Override
        public void beforeOutputChange(Iterable<String> affectedOutputPaths) {
            // A very dumb strategy for invalidating cache
            locationCache.clear();
        }

        @Override
        public V get(File file) {
            return locationCache.computeIfAbsent(file,
                location -> fileSystemAccess.readRegularFileContentHash(
                    location.getAbsolutePath(),
                    contentHash -> contentCache.get(contentHash, key -> calculator.calculate(location, true))
                ).orElseGet(
                    () -> calculator.calculate(location, false)
                ));
        }

        private void assertStoredIn(PersistentIndexedCache<HashCode, V> store) {
            if (this.contentCache != store) {
                throw new IllegalStateException("Cache " + name + " cannot be recreated with different parameters");
            }
        }
    }
}
