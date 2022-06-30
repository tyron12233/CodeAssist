package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.internal.changedetection.state.DefaultFileAccessTimeJournal;
import com.tyron.builder.api.internal.classpath.ModuleRegistry;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.GradleUserHomeTemporaryFileProvider;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderCache;
import com.tyron.builder.api.internal.initialization.loadercache.DefaultClassLoaderCache;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.GlobalCache;
import com.tyron.builder.cache.GlobalCacheLocations;
import com.tyron.builder.cache.internal.CacheFactory;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheRepository;
import com.tyron.builder.cache.internal.DefaultFileContentCacheFactory;
import com.tyron.builder.cache.internal.DefaultGeneratedGradleJarCache;
import com.tyron.builder.cache.internal.DefaultGlobalCacheLocations;
import com.tyron.builder.cache.internal.DefaultInMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.FileContentCacheFactory;
import com.tyron.builder.cache.internal.GradleUserHomeCleanupServices;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.scopes.DefaultCacheScopeMapping;
import com.tyron.builder.cache.internal.scopes.DefaultGlobalScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.initialization.ClassLoaderRegistry;
import com.tyron.builder.initialization.ClassLoaderScopeRegistry;
import com.tyron.builder.initialization.ClassLoaderScopeRegistryListenerManager;
import com.tyron.builder.initialization.DefaultClassLoaderScopeRegistry;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.initialization.layout.GlobalCacheDir;
import com.tyron.builder.internal.classloader.ClasspathHasher;
import com.tyron.builder.internal.classloader.ConfigurableClassLoaderHierarchyHasher;
import com.tyron.builder.internal.classloader.DefaultHashingClassLoaderFactory;
import com.tyron.builder.internal.classloader.HashingClassLoaderFactory;
import com.tyron.builder.internal.classpath.ClasspathBuilder;
import com.tyron.builder.internal.classpath.ClasspathWalker;
import com.tyron.builder.internal.classpath.DefaultCachedClasspathTransformer;
import com.tyron.builder.internal.classpath.DefaultClasspathTransformerCacheFactory;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.execution.timeout.impl.DefaultTimeoutHandler;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.id.LongIdGenerator;
import com.tyron.builder.internal.jvm.JavaModuleDetector;
import com.tyron.builder.internal.jvm.inspection.JvmVersionDetector;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.remote.MessagingServer;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.process.internal.ExecFactory;
import com.tyron.builder.process.internal.JavaExecHandleFactory;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.worker.DefaultWorkerProcessFactory;
import com.tyron.builder.process.internal.worker.WorkerProcessFactory;
import com.tyron.builder.util.GradleVersion;
import com.tyron.common.TestUtil;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

    ClassLoaderHierarchyHasher createClassLoaderHierarchyHasher(HashingClassLoaderFactory hashingClassLoaderFactory,
                                                                ClassLoaderScopeRegistry classLoaderScopeRegistry) {
        Map<ClassLoader, String> classLoaderStringMap = new WeakHashMap<>();
        classLoaderStringMap.put(ClassLoader.getSystemClassLoader(), "system");
        classLoaderStringMap
                .put(classLoaderScopeRegistry.getCoreAndPluginsScope().getExportClassLoader(),
                        "plugins");
        return new ConfigurableClassLoaderHierarchyHasher(classLoaderStringMap,
                hashingClassLoaderFactory);
    }

    HashingClassLoaderFactory createClassLoaderFactory(ClasspathHasher classpathHasher) {
        return new DefaultHashingClassLoaderFactory(classpathHasher);
    }

    DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
        return parent.createChild(Scopes.UserHome.class);
    }

    ClassLoaderCache createClassLoaderCache(HashingClassLoaderFactory classLoaderFactory,
                                            ClasspathHasher classpathHasher,
                                            ListenerManager listenerManager) {
        DefaultClassLoaderCache cache =
                new DefaultClassLoaderCache(classLoaderFactory, classpathHasher);
        listenerManager.addListener(cache);
        return cache;
    }

    protected ClassLoaderScopeRegistryListenerManager createClassLoaderScopeRegistryListenerManager(
            ListenerManager listenerManager) {
        return new ClassLoaderScopeRegistryListenerManager(listenerManager);
    }

    protected ClassLoaderScopeRegistry createClassLoaderScopeRegistry(ClassLoaderRegistry classLoaderRegistry,
                                                                      ClassLoaderCache classLoaderCache,
                                                                      ClassLoaderScopeRegistryListenerManager listenerManager) {
        return new DefaultClassLoaderScopeRegistry(classLoaderRegistry, classLoaderCache,
                listenerManager.getBroadcaster());
    }

    GlobalScopedCache createGlobalScopedCache(GlobalCacheDir globalCacheDir,
                                              CacheRepository cache) {
        return new DefaultGlobalScopedCache(globalCacheDir.getDir(), cache);
    }

    InMemoryCacheDecoratorFactory createInMemoryCacheDecoratorFactory(CrossBuildInMemoryCacheFactory crossBuildInMemoryCacheFactory) {
        return new DefaultInMemoryCacheDecoratorFactory(true, crossBuildInMemoryCacheFactory);
    }

    CacheFactory createCacheFactory(FileLockManager fileLockManager,
                                    ExecutorFactory executorFactory,
                                    ProgressLoggerFactory progressLoggerFactory) {
        return new DefaultCacheFactory(fileLockManager, executorFactory, progressLoggerFactory);
    }

    CacheScopeMapping createCacheScopeMapping() {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        return new DefaultCacheScopeMapping(resourcesDirectory, GradleVersion.current());
    }

    CacheRepository createCacheRepository(CacheScopeMapping scopeMapping,
                                          CacheFactory cacheFactory) {
        return new DefaultCacheRepository(scopeMapping, cacheFactory);
    }

    FileAccessTimeJournal createFileAccessTimeJournal(GlobalScopedCache cacheRepository,
                                                      InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        return new DefaultFileAccessTimeJournal(cacheRepository, cacheDecoratorFactory);
    }

    GlobalCacheLocations createGlobalCacheLocations(List<GlobalCache> globalCaches) {
        return new DefaultGlobalCacheLocations(globalCaches);
    }

    ExecFactory createExecFactory(ExecFactory parent, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, ObjectFactory objectFactory, JavaModuleDetector javaModuleDetector) {
        return parent.forContext(fileResolver, fileCollectionFactory, instantiator, objectFactory, javaModuleDetector);
    }

    WorkerProcessFactory createWorkerProcessFactory(
            LoggingManagerInternal loggingManagerInternal, MessagingServer messagingServer, ClassPathRegistry classPathRegistry,
            TemporaryFileProvider temporaryFileProvider, JavaExecHandleFactory execHandleFactory, JvmVersionDetector jvmVersionDetector,
            MemoryManager memoryManager, GradleUserHomeDirProvider gradleUserHomeDirProvider, OutputEventListener outputEventListener
    ) {
        return new DefaultWorkerProcessFactory(
                loggingManagerInternal,
                messagingServer,
                classPathRegistry,
                new LongIdGenerator(),
                gradleUserHomeDirProvider.getGradleUserHomeDirectory(),
                temporaryFileProvider,
                execHandleFactory,
                jvmVersionDetector,
                outputEventListener,
                memoryManager
        );
    }

//    WorkerProcessClassPathProvider createWorkerProcessClassPathProvider(GlobalScopedCache cacheRepository, ModuleRegistry moduleRegistry) {
//        return new WorkerProcessClassPathProvider(cacheRepository, moduleRegistry);
//    }

    protected JavaModuleDetector createJavaModuleDetector(FileContentCacheFactory cacheFactory, FileCollectionFactory fileCollectionFactory) {
        return new JavaModuleDetector(cacheFactory, fileCollectionFactory);
    }


    DefaultGeneratedGradleJarCache createGeneratedGradleJarCache(GlobalScopedCache cacheRepository) {
        String gradleVersion = GradleVersion.current().getVersion();
        return new DefaultGeneratedGradleJarCache(cacheRepository, gradleVersion);
    }

    FileContentCacheFactory createFileContentCacheFactory(ListenerManager listenerManager, FileSystemAccess fileSystemAccess, GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        return new DefaultFileContentCacheFactory(listenerManager, fileSystemAccess, cacheRepository, inMemoryCacheDecoratorFactory);
    }


    private interface CacheDirectoryProvider {
        File getCacheDirectory();
    }

    TimeoutHandler createTimeoutHandler(ExecutorFactory executorFactory, CurrentBuildOperationRef currentBuildOperationRef) {
        return new DefaultTimeoutHandler(executorFactory.createScheduled("execution timeouts", 1), currentBuildOperationRef);
    }
}
