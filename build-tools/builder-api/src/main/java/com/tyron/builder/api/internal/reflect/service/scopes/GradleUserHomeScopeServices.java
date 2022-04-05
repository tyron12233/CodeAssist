package com.tyron.builder.api.internal.reflect.service.scopes;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.classpath.ClassPath;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.hash.ConfigurableClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.hash.Hashes;
import com.tyron.builder.api.internal.hash.HashingClassLoaderFactory;
import com.tyron.builder.api.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.cache.internal.CacheFactory;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheRepository;
import com.tyron.builder.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.DefaultInMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;
import com.tyron.builder.cache.internal.scopes.DefaultBuildScopedCache;
import com.tyron.builder.cache.internal.scopes.DefaultCacheScopeMapping;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.common.TestUtil;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;

public class GradleUserHomeScopeServices extends DefaultServiceRegistry {

    public GradleUserHomeScopeServices(ServiceRegistry parent) {
        super(parent);
    }

    HashingClassLoaderFactory createHashingClassLoaderFactory() {
        return new HashingClassLoaderFactory() {
            @Override
            public ClassLoader createChildClassLoader(String name,
                                                      ClassLoader parent,
                                                      ClassPath classPath,
                                                      @Nullable HashCode implementationHash) {
                return parent;
            }

            @Nullable
            @Override
            public HashCode getClassLoaderClasspathHash(ClassLoader classLoader) {
                return Hashes.signature(classLoader.getClass().getName());
            }

            @Override
            public ClassLoader getIsolatedSystemClassLoader() {
                return null;
            }

            @Override
            public ClassLoader createIsolatedClassLoader(String name, ClassPath classPath) {
                return null;
            }
        };
    }

    ClassLoaderHierarchyHasher createClassLoaderHierarchyHasher(
            HashingClassLoaderFactory hashingClassLoaderFactory
    ) {
        return new ConfigurableClassLoaderHierarchyHasher(Collections.emptyMap(), hashingClassLoaderFactory);
    }

    CrossBuildInMemoryCacheFactory createCrossBuildInMemoryFactory(
            ListenerManager listenerManager
    ) {
        return new DefaultCrossBuildInMemoryCacheFactory(listenerManager);
    }

    InMemoryCacheDecoratorFactory createInMemoryCacheDecoratorFactory(
            CrossBuildInMemoryCacheFactory crossBuildInMemoryCacheFactory
    ) {
        return new DefaultInMemoryCacheDecoratorFactory(true, crossBuildInMemoryCacheFactory);
    }

    FileLockManager createFileLockManager() {
        return new DefaultFileLockManager(new ProcessMetaDataProvider() {
            @Override
            public String getProcessIdentifier() {
                return "TEST";
            }

            @Override
            public String getProcessDisplayName() {
                return "TEST";
            }
        }, new FileLockContentionHandler() {
            @Override
            public void start(long lockId, Action<FileLockReleasedSignal> whenContended) {

            }

            @Override
            public void stop(long lockId) {

            }

            @Override
            public int reservePort() {
                return 0;
            }

            @Override
            public boolean maybePingOwner(int port,
                                          long lockId,
                                          String displayName,
                                          long timeElapsed,
                                          @Nullable FileLockReleasedSignal signal) {
                return false;
            }
        });
    }

    ExecutorFactory createExecutorFactory() {
        return new DefaultExecutorFactory();
    }

    ProgressLoggerFactory createProgressLoggerFactory() {
        return ProgressLoggerFactory.EMPTY;
    }

    CacheFactory createCacheFactory(
            FileLockManager fileLockManager,
            ExecutorFactory executorFactory,
            ProgressLoggerFactory progressLoggerFactory
    ) {
        return new DefaultCacheFactory(fileLockManager, executorFactory, progressLoggerFactory);
    }

    CacheScopeMapping createCacheScopeMapping() {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        return new DefaultCacheScopeMapping(resourcesDirectory, DocumentationRegistry.GradleVersion.current());
    }

    CacheRepository createCacheRepository(
            CacheScopeMapping scopeMapping,
            CacheFactory cacheFactory
    ) {
        return new DefaultCacheRepository(scopeMapping, cacheFactory);
    }

    protected BuildScopedCache createBuildScopedCache(
            CacheRepository cacheRepository
    ) {
        File test = TestUtil.getResourcesDirectory();
        return new DefaultBuildScopedCache(test, cacheRepository);
    }
}
