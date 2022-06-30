package com.tyron.builder.api.internal.tasks.compile.incremental.cache;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.serialize.HashCodeSerializer;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassAnalysis;
import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;
import com.tyron.builder.api.internal.tasks.compile.incremental.serialization.HierarchicalNameSerializer;
import com.tyron.builder.cache.Cache;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.MinimalPersistentCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;

import java.io.Closeable;

public class UserHomeScopedCompileCaches implements GeneralCompileCaches, Closeable {
    private final Cache<HashCode, ClassSetAnalysisData> classpathEntrySnapshotCache;
    private final PersistentCache cache;
    private final Cache<HashCode, ClassAnalysis> classAnalysisCache;

    public UserHomeScopedCompileCaches(GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, StringInterner interner) {
        cache = cacheRepository
                .cache("javaCompile")
                .withDisplayName("Java compile cache")
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Lock on demand
                .open();
        PersistentIndexedCacheParameters<HashCode, ClassSetAnalysisData> jarCacheParameters = PersistentIndexedCacheParameters.of(
                "jarAnalysis",
                new HashCodeSerializer(),
                new ClassSetAnalysisData.Serializer(() -> new HierarchicalNameSerializer(interner))
        ).withCacheDecorator(inMemoryCacheDecoratorFactory.decorator(20000, true));
        this.classpathEntrySnapshotCache = new MinimalPersistentCache<>(cache.createCache(jarCacheParameters));

        PersistentIndexedCacheParameters<HashCode, ClassAnalysis> classCacheParameters = PersistentIndexedCacheParameters.of(
                "classAnalysis",
                new HashCodeSerializer(),
                new ClassAnalysis.Serializer(interner)
        ).withCacheDecorator(inMemoryCacheDecoratorFactory.decorator(400000, true));
        this.classAnalysisCache = new MinimalPersistentCache<>(cache.createCache(classCacheParameters));
    }

    @Override
    public void close() {
        cache.close();
    }

    @Override
    public Cache<HashCode, ClassSetAnalysisData> getClassSetAnalysisCache() {
        return classpathEntrySnapshotCache;
    }

    @Override
    public Cache<HashCode, ClassAnalysis> getClassAnalysisCache() {
        return classAnalysisCache;
    }
}

