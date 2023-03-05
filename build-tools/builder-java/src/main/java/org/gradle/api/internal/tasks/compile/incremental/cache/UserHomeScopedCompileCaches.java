package org.gradle.api.internal.tasks.compile.incremental.cache;

import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

import com.google.common.hash.HashCode;
import org.gradle.internal.serialize.HashCodeSerializer;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassAnalysis;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;
import org.gradle.api.internal.tasks.compile.incremental.serialization.HierarchicalNameSerializer;
import org.gradle.cache.Cache;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.PersistentIndexedCacheParameters;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.MinimalPersistentCache;
import org.gradle.cache.scopes.GlobalScopedCache;

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

