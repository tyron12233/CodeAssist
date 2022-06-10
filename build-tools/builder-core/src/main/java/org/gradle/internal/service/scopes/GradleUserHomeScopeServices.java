package org.gradle.internal.service.scopes;

import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.changedetection.state.DefaultFileAccessTimeJournal;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.GradleUserHomeTemporaryFileProvider;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.api.internal.initialization.loadercache.DefaultClassLoaderCache;
import org.gradle.api.model.ObjectFactory;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.GlobalCache;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.internal.CacheFactory;
import org.gradle.cache.internal.CacheScopeMapping;
import org.gradle.cache.internal.CrossBuildInMemoryCacheFactory;
import org.gradle.cache.internal.DefaultCacheFactory;
import org.gradle.cache.internal.DefaultCacheRepository;
import org.gradle.cache.internal.DefaultFileContentCacheFactory;
import org.gradle.cache.internal.DefaultGeneratedGradleJarCache;
import org.gradle.cache.internal.DefaultGlobalCacheLocations;
import org.gradle.cache.internal.DefaultInMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.cache.internal.GradleUserHomeCleanupServices;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.scopes.DefaultCacheScopeMapping;
import org.gradle.cache.internal.scopes.DefaultGlobalScopedCache;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.ClassLoaderScopeRegistry;
import org.gradle.initialization.ClassLoaderScopeRegistryListenerManager;
import org.gradle.initialization.DefaultClassLoaderScopeRegistry;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.layout.GlobalCacheDir;
import org.gradle.internal.classloader.ClasspathHasher;
import org.gradle.internal.classloader.ConfigurableClassLoaderHierarchyHasher;
import org.gradle.internal.classloader.DefaultHashingClassLoaderFactory;
import org.gradle.internal.classloader.HashingClassLoaderFactory;
import org.gradle.internal.classpath.ClasspathBuilder;
import org.gradle.internal.classpath.ClasspathWalker;
import org.gradle.internal.classpath.DefaultCachedClasspathTransformer;
import org.gradle.internal.classpath.DefaultClasspathTransformerCacheFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.timeout.TimeoutHandler;
import org.gradle.internal.execution.timeout.impl.DefaultTimeoutHandler;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.jvm.JavaModuleDetector;
import org.gradle.internal.jvm.inspection.JvmVersionDetector;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.process.internal.ExecFactory;
import org.gradle.process.internal.JavaExecHandleFactory;
import org.gradle.process.internal.health.memory.MemoryManager;
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.util.GradleVersion;
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
