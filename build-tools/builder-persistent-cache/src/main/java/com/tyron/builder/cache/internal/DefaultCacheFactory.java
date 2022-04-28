package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheOpenException;
import com.tyron.builder.cache.CleanupAction;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.LockOptions;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultCacheFactory implements CacheFactory, Closeable {
    private final Map<File, DirCacheReference> dirCaches = new HashMap<File, DirCacheReference>();
    private final FileLockManager lockManager;
    private final ExecutorFactory executorFactory;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final Lock lock = new ReentrantLock();

    public DefaultCacheFactory(FileLockManager fileLockManager, ExecutorFactory executorFactory, ProgressLoggerFactory progressLoggerFactory) {
        this.lockManager = fileLockManager;
        this.executorFactory = executorFactory;
        this.progressLoggerFactory = progressLoggerFactory;
    }

    void onOpen(Object cache) {
    }

    void onClose(Object cache) {
    }

    @Override
    public PersistentCache open(File cacheDir, String displayName, Map<String, ?> properties, CacheBuilder.LockTarget lockTarget, LockOptions lockOptions, Action<? super PersistentCache> initializer, CleanupAction cleanup) throws CacheOpenException {
        lock.lock();
        try {
            return doOpen(cacheDir, displayName, properties, lockTarget, lockOptions, initializer, cleanup);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            CompositeStoppable.stoppable(dirCaches.values()).stop();
        } finally {
            dirCaches.clear();
            lock.unlock();
        }
    }

    private PersistentCache doOpen(
            File cacheDir,
            String displayName,
            Map<String, ?> properties,
            CacheBuilder.LockTarget lockTarget,
            LockOptions lockOptions,
            @Nullable Action<? super PersistentCache> initializer,
            @Nullable CleanupAction cleanup
    ) {
        File canonicalDir = GFileUtils.canonicalize(cacheDir);
        DirCacheReference dirCacheReference = dirCaches.get(canonicalDir);
        if (dirCacheReference == null) {
            ReferencablePersistentCache cache;
            if (!properties.isEmpty() || initializer != null) {
                cache = new DefaultPersistentDirectoryCache(canonicalDir, displayName, properties, lockTarget, lockOptions, initializer, cleanup, lockManager, executorFactory, progressLoggerFactory);
            } else {
                cache = new DefaultPersistentDirectoryStore(canonicalDir, displayName, lockTarget, lockOptions, cleanup, lockManager, executorFactory, progressLoggerFactory);
            }
            cache.open();
            dirCacheReference = new DirCacheReference(cache, properties, lockTarget, lockOptions);
            dirCaches.put(canonicalDir, dirCacheReference);
        } else {
            if (!lockOptions.equals(dirCacheReference.lockOptions)) {
                throw new IllegalStateException(String.format("Cache '%s' is already open with different lock options.", cacheDir));
            }
            if (lockTarget != dirCacheReference.lockTarget) {
                throw new IllegalStateException(String.format("Cache '%s' is already open with different lock target.", cacheDir));
            }
            if (!properties.equals(dirCacheReference.properties)) {
                throw new IllegalStateException(String.format("Cache '%s' is already open with different properties.", cacheDir));
            }
        }
        return new ReferenceTrackingCache(dirCacheReference);
    }

    private class DirCacheReference implements Closeable {
        private final Map<String, ?> properties;
        private final CacheBuilder.LockTarget lockTarget;
        private final LockOptions lockOptions;
        private final ReferencablePersistentCache cache;
        private final Set<ReferenceTrackingCache> references = new HashSet<>();

        DirCacheReference(ReferencablePersistentCache cache, Map<String, ?> properties, CacheBuilder.LockTarget lockTarget, LockOptions lockOptions) {
            this.cache = cache;
            this.properties = properties;
            this.lockTarget = lockTarget;
            this.lockOptions = lockOptions;
            onOpen(cache);
        }

        public void addReference(ReferenceTrackingCache cache) {
            references.add(cache);
        }

        public void release(ReferenceTrackingCache cache) {
            lock.lock();
            try {
                if (references.remove(cache) && references.isEmpty()) {
                    close();
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            onClose(cache);
            dirCaches.values().remove(this);
            references.clear();
            cache.close();
        }
    }

    private static class ReferenceTrackingCache implements PersistentCache {
        private final DirCacheReference reference;

        private ReferenceTrackingCache(DirCacheReference reference) {
            this.reference = reference;
            reference.addReference(this);
        }

        @Override
        public String toString() {
            return reference.cache.toString();
        }

        @Override
        public void close() {
            reference.release(this);
        }

        @Override
        public String getDisplayName() {
            return reference.cache.toString();
        }

        @Override
        public File getBaseDir() {
            return reference.cache.getBaseDir();
        }

        @Override
        public Collection<File> getReservedCacheFiles() {
            return reference.cache.getReservedCacheFiles();
        }

        @Override
        public <K, V> PersistentIndexedCache<K, V> createCache(PersistentIndexedCacheParameters<K, V> parameters) {
            return reference.cache.createCache(parameters);
        }

        @Override
        public <K, V> PersistentIndexedCache<K, V> createCache(String name, Class<K> keyType, Serializer<V> valueSerializer) {
            return reference.cache.createCache(name, keyType, valueSerializer);
        }

        @Override
        public <K, V> boolean cacheExists(PersistentIndexedCacheParameters<K, V> parameters) {
            return reference.cache.cacheExists(parameters);
        }

        @Override
        public <T> T withFileLock(Factory<? extends T> action) {
            return reference.cache.withFileLock(action);
        }

        @Override
        public void withFileLock(Runnable action) {
            reference.cache.withFileLock(action);
        }

        @Override
        public <T> T useCache(Factory<? extends T> action) {
            return reference.cache.useCache(action);
        }

        @Override
        public void useCache(Runnable action) {
            reference.cache.useCache(action);
        }
    }
}