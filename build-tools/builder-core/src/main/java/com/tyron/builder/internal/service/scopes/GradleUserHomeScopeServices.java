package com.tyron.builder.internal.service.scopes;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.changedetection.state.CrossBuildFileHashCache;
import com.tyron.builder.api.internal.changedetection.state.DefaultFileAccessTimeJournal;
import com.tyron.builder.api.internal.file.temp.GradleUserHomeTemporaryFileProvider;
import com.tyron.builder.cache.GlobalCache;
import com.tyron.builder.cache.GlobalCacheLocations;
import com.tyron.builder.cache.internal.DefaultGlobalCacheLocations;
import com.tyron.builder.cache.internal.GradleUserHomeCleanupServices;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.classpath.ClasspathBuilder;
import com.tyron.builder.internal.classpath.ClasspathWalker;
import com.tyron.builder.internal.classpath.DefaultCachedClasspathTransformer;
import com.tyron.builder.internal.classpath.DefaultClasspathTransformerCacheFactory;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.classloader.ConfigurableClassLoaderHierarchyHasher;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.classloader.HashingClassLoaderFactory;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.internal.CacheFactory;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheRepository;
import com.tyron.builder.cache.internal.DefaultInMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.scopes.DefaultCacheScopeMapping;
import com.tyron.builder.cache.internal.scopes.DefaultGlobalScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.initialization.layout.GlobalCacheDir;
import com.tyron.common.TestUtil;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class GradleUserHomeScopeServices extends WorkerSharedUserHomeScopeServices {
    
    private final ServiceRegistry globalServices;

    public GradleUserHomeScopeServices(ServiceRegistry globalServices) {
        this.globalServices = globalServices;
    }

    public void configure(ServiceRegistration registration) {
        registration.add(GlobalCacheDir.class);
        registration.addProvider(new GradleUserHomeCleanupServices());
        registration.add(ClasspathWalker.class);
        registration.add(ClasspathBuilder.class);
        registration.add(GradleUserHomeTemporaryFileProvider.class);
        registration.add(DefaultClasspathTransformerCacheFactory.class);
//        registration.add(GradleUserHomeScopeFileTimeStampInspector.class);
        registration.add(DefaultCachedClasspathTransformer.class);
        for (PluginServiceRegistry plugin : globalServices.getAll(PluginServiceRegistry.class)) {
            plugin.registerGradleUserHomeServices(registration);
        }
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

    GlobalScopedCache createGlobalScopedCache(
            GlobalCacheDir globalCacheDir,
            CacheRepository cache
    ) {
        return new DefaultGlobalScopedCache(globalCacheDir.getDir(), cache);
    }

    InMemoryCacheDecoratorFactory createInMemoryCacheDecoratorFactory(
            CrossBuildInMemoryCacheFactory crossBuildInMemoryCacheFactory
    ) {
        return new DefaultInMemoryCacheDecoratorFactory(true, crossBuildInMemoryCacheFactory);
    }

    CrossBuildFileHashCache createCrossBuildFileHashCache(
            GlobalScopedCache scopedCache,
            InMemoryCacheDecoratorFactory factory
    ) {
        return new CrossBuildFileHashCache(scopedCache, factory, CrossBuildFileHashCache.Kind.FILE_HASHES);
    }


    CacheFactory createCacheFactory(
            FileLockManager fileLockManager,
            ExecutorFactory executorFactory,
            ProgressLoggerFactory progressLoggerFactory
    ) {
        return new DefaultCacheFactory(fileLockManager, executorFactory, progressLoggerFactory);
    }

    CacheScopeMapping createCacheScopeMapping(
    ) {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        return new DefaultCacheScopeMapping(resourcesDirectory, DocumentationRegistry.GradleVersion.current());
    }

    CacheRepository createCacheRepository(
            CacheScopeMapping scopeMapping,
            CacheFactory cacheFactory
    ) {
        return new DefaultCacheRepository(scopeMapping, cacheFactory);
    }

    FileAccessTimeJournal createFileAccessTimeJournal(GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        return new DefaultFileAccessTimeJournal(cacheRepository, cacheDecoratorFactory);
    }

    GlobalCacheLocations createGlobalCacheLocations(List<GlobalCache> globalCaches) {
        return new DefaultGlobalCacheLocations(globalCaches);
    }

    private interface CacheDirectoryProvider {
        File getCacheDirectory();
    }
}
