package com.tyron.builder.internal.service.scopes;

import com.google.common.collect.Iterables;
import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultClassPathProvider;
import com.tyron.builder.api.internal.DefaultClassPathRegistry;
import com.tyron.builder.api.internal.DynamicModulesClassPathProvider;
import com.tyron.builder.api.internal.MutationGuards;
import com.tyron.builder.api.internal.classpath.DefaultModuleRegistry;
import com.tyron.builder.api.internal.classpath.DefaultPluginModuleRegistry;
import com.tyron.builder.api.internal.classpath.ModuleRegistry;
import com.tyron.builder.api.internal.classpath.PluginModuleRegistry;
import com.tyron.builder.api.internal.collections.DefaultDomainObjectCollectionFactory;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.tasks.properties.annotations.AbstractOutputPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputPropertyRoleAnnotationHandler;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.cache.internal.CleaningInMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.configuration.DefaultImportsReader;
import com.tyron.builder.configuration.ImportsReader;
import com.tyron.builder.execution.DefaultWorkValidationWarningRecorder;
import com.tyron.builder.initialization.ClassLoaderRegistry;
import com.tyron.builder.initialization.DefaultClassLoaderRegistry;
import com.tyron.builder.initialization.DefaultJdkToolsInitializer;
import com.tyron.builder.initialization.JdkToolsInitializer;
import com.tyron.builder.initialization.LegacyTypesSupport;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.classloader.DefaultClassLoaderFactory;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.changes.DefaultExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.history.impl.DefaultOverlappingOutputDetector;
import com.tyron.builder.internal.execution.steps.WorkInputListeners;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.internal.hash.DefaultFileHasher;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.installation.CurrentGradleInstallation;
import com.tyron.builder.internal.installation.GradleRuntimeShadedJarDetector;
import com.tyron.builder.internal.instantiation.InjectAnnotationHandler;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.instantiation.generator.DefaultInstantiatorFactory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.operations.BuildOperationListener;
import com.tyron.builder.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.operations.DefaultBuildOperationListenerManager;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.internal.model.DefaultObjectFactory;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.internal.service.DefaultServiceLocator;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;
import com.tyron.builder.internal.watch.vfs.FileChangeListeners;
import com.tyron.builder.model.internal.inspect.MethodModelRuleExtractor;
import com.tyron.builder.model.internal.inspect.MethodModelRuleExtractors;
import com.tyron.builder.model.internal.inspect.ModelRuleExtractor;
import com.tyron.builder.model.internal.inspect.ModelRuleSourceDetector;
import com.tyron.builder.model.internal.manage.binding.DefaultStructBindingsStore;
import com.tyron.builder.model.internal.manage.binding.StructBindingsStore;
import com.tyron.builder.model.internal.manage.instance.ManagedProxyFactory;
import com.tyron.builder.model.internal.manage.schema.ModelSchemaStore;
import com.tyron.builder.model.internal.manage.schema.extract.DefaultModelSchemaExtractor;
import com.tyron.builder.model.internal.manage.schema.extract.DefaultModelSchemaStore;
import com.tyron.builder.model.internal.manage.schema.extract.ModelSchemaAspectExtractionStrategy;
import com.tyron.builder.model.internal.manage.schema.extract.ModelSchemaAspectExtractor;
import com.tyron.builder.model.internal.manage.schema.extract.ModelSchemaExtractionStrategy;
import com.tyron.builder.model.internal.manage.schema.extract.ModelSchemaExtractor;
import com.tyron.builder.process.internal.health.memory.DefaultJvmMemoryInfo;
import com.tyron.builder.process.internal.health.memory.DefaultMemoryManager;
import com.tyron.builder.process.internal.health.memory.DefaultOsMemoryInfo;
import com.tyron.builder.process.internal.health.memory.JvmMemoryInfo;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.health.memory.OsMemoryInfo;

import net.rubygrapefruit.platform.file.FileSystems;
import net.rubygrapefruit.platform.internal.PosixFileSystems;

import java.io.File;
import java.util.List;

public class GlobalServices extends WorkerSharedGlobalScopeServices {

    protected final ClassPath additionalModuleClassPath;

    public GlobalServices(final boolean longLiving) {
        this(longLiving, ClassPath.EMPTY);
    }

    public GlobalServices(final boolean longLiving, ClassPath additionalModuleClassPath) {
        super();
        this.additionalModuleClassPath = additionalModuleClassPath;
//        this.environment = () -> longLiving;
    }


    void configure(ServiceRegistration registration, List<String> somethingEmpty) {
        final List<PluginServiceRegistry> pluginServiceFactories = new DefaultServiceLocator(getClass().getClassLoader()).getAll(PluginServiceRegistry.class);
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceFactories) {
            registration.add(PluginServiceRegistry.class, pluginServiceRegistry);
            pluginServiceRegistry.registerGlobalServices(registration);
        }
        registration.add(BuildLayoutFactory.class);
    }


    // TODO: move this to DependencyManagementServices

    TextUriResourceLoader.Factory createTextUrlResourceLoaderFactory() {
       return new TextUriResourceLoader.Factory() {
           @Override
           public TextUriResourceLoader create(HttpRedirectVerifier redirectVerifier) {
               throw new UnsupportedOperationException("");
           }
       };
    }

    BuildOperationProgressEventEmitter createBuildOperationProgressEventEmitter(
            Clock clock,
            CurrentBuildOperationRef currentBuildOperationRef,
            BuildOperationListenerManager listenerManager
    ) {
        return new BuildOperationProgressEventEmitter(
                clock,
                currentBuildOperationRef,
                listenerManager.getBroadcaster()
        );
    }

    GradleUserHomeScopeServiceRegistry createGradleUserHomeScopeServiceRegistry(ServiceRegistry globalServices) {
        return new DefaultGradleUserHomeScopeServiceRegistry(globalServices, new GradleUserHomeScopeServices(globalServices));
    }

    CurrentBuildOperationRef createCurrentBuildOperationRef() {
        return CurrentBuildOperationRef.instance();
    }

    BuildOperationListenerManager createBuildOperationListenerManager() {
        return new DefaultBuildOperationListenerManager();
    }

    WorkInputListeners createWorkInputListeners(
            ListenerManager listenerManager
    ) {
        return new DefaultWorkInputListeners(listenerManager);
    }

    LoggingManagerInternal createLoggingManager(Factory<LoggingManagerInternal> loggingManagerFactory) {
        return loggingManagerFactory.create();
    }

    ExecutionStateChangeDetector createExecutionStateChangeDetector() {
        return new DefaultExecutionStateChangeDetector();
    }

    BuildOperationListener createBuildOperationListener(
            ListenerManager listenerManager
    ) {
        return listenerManager.getBroadcaster(BuildOperationListener.class);
    }

    JdkToolsInitializer createJdkToolsInitializer() {
        return new DefaultJdkToolsInitializer(new DefaultClassLoaderFactory());
    }

    InstanceGenerator createInstantiator(InstantiatorFactory instantiatorFactory) {
        return instantiatorFactory.decorateLenient();
    }

    InMemoryCacheDecoratorFactory createInMemoryTaskArtifactCache(CrossBuildInMemoryCacheFactory cacheFactory) {
        return new CleaningInMemoryCacheDecoratorFactory(true, cacheFactory);
    }

    ModelRuleExtractor createModelRuleInspector(List<MethodModelRuleExtractor> extractors, ModelSchemaStore modelSchemaStore, StructBindingsStore structBindingsStore, ManagedProxyFactory managedProxyFactory) {
        List<MethodModelRuleExtractor> coreExtractors = MethodModelRuleExtractors.coreExtractors(modelSchemaStore);
        return new ModelRuleExtractor(Iterables.concat(coreExtractors, extractors), managedProxyFactory, modelSchemaStore, structBindingsStore);
    }

    protected ModelSchemaAspectExtractor createModelSchemaAspectExtractor(List<ModelSchemaAspectExtractionStrategy> strategies) {
        return new ModelSchemaAspectExtractor(strategies);
    }

    protected ManagedProxyFactory createManagedProxyFactory() {
        return new ManagedProxyFactory();
    }

    protected ModelSchemaExtractor createModelSchemaExtractor(ModelSchemaAspectExtractor aspectExtractor, List<ModelSchemaExtractionStrategy> strategies) {
        return DefaultModelSchemaExtractor.withDefaultStrategies(strategies, aspectExtractor);
    }

    FileHasher createFileHasher(
            StreamHasher streamHasher
    ) {
        return new DefaultFileHasher(streamHasher);
    }

    FileChangeListeners createFileChangeListeners(ListenerManager listenerManager) {
        return new DefaultFileChangeListeners(listenerManager);
    }

    FileSystems createFileSystems() {
        return new PosixFileSystems();
    }

    protected ModelSchemaStore createModelSchemaStore(ModelSchemaExtractor modelSchemaExtractor) {
        return new DefaultModelSchemaStore(modelSchemaExtractor);
    }

    protected StructBindingsStore createStructBindingsStore(ModelSchemaStore schemaStore) {
        return new DefaultStructBindingsStore(schemaStore);
    }

    protected ModelRuleSourceDetector createModelRuleSourceDetector() {
        return new ModelRuleSourceDetector();
    }

    FileSystem createFileSystem() {
        return new FileSystem() {
            @Override
            public boolean isCaseSensitive() {
                return true;
            }

            @Override
            public boolean canCreateSymbolicLink() {
                return false;
            }

            @Override
            public void createSymbolicLink(File link, File target) throws FileException {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSymlink(File suspect) {
                return false;
            }

            @Override
            public void chmod(File file, int mode) throws FileException {

            }

            @Override
            public int getUnixMode(File f) throws FileException {
                return 0;
            }

            @Override
            public FileMetadata stat(File f) throws FileException {
                if (!f.exists()) {
                    return DefaultFileMetadata.missing(FileMetadata.AccessType.DIRECT);
                }
                if (f.isDirectory()) {
                    return DefaultFileMetadata.directory(FileMetadata.AccessType.DIRECT);
                }
                return DefaultFileMetadata.file(f.lastModified(), f.length(), FileMetadata.AccessType.DIRECT);
            }
        };
    }

    protected ImportsReader createImportsReader() {
        return new DefaultImportsReader();
    }

    StringInterner createStringInterner() {
        return new StringInterner();
    }

    InstantiatorFactory createInstantiatorFactory(CrossBuildInMemoryCacheFactory cacheFactory, List<InjectAnnotationHandler> injectHandlers, List<AbstractOutputPropertyAnnotationHandler> outputHandlers) {
        return new DefaultInstantiatorFactory(cacheFactory, injectHandlers, new OutputPropertyRoleAnnotationHandler(outputHandlers));
    }

    OsMemoryInfo createOsMemoryInfo() {
        return new DefaultOsMemoryInfo();
    }

    JvmMemoryInfo createJvmMemoryInfo() {
        return new DefaultJvmMemoryInfo();
    }

    MemoryManager createMemoryManager(OsMemoryInfo osMemoryInfo, JvmMemoryInfo jvmMemoryInfo, ListenerManager listenerManager, ExecutorFactory executorFactory) {
        return new DefaultMemoryManager(osMemoryInfo, jvmMemoryInfo, listenerManager, executorFactory);
    }

    ObjectFactory createObjectFactory(
            InstantiatorFactory instantiatorFactory, ServiceRegistry services, DirectoryFileTreeFactory directoryFileTreeFactory, Factory<PatternSet> patternSetFactory,
            PropertyFactory propertyFactory, FilePropertyFactory filePropertyFactory, FileCollectionFactory fileCollectionFactory,
            DomainObjectCollectionFactory domainObjectCollectionFactory, NamedObjectInstantiator instantiator
    ) {
        return new DefaultObjectFactory(
                instantiatorFactory.decorate(services),
                instantiator,
                directoryFileTreeFactory,
                patternSetFactory,
                propertyFactory,
                filePropertyFactory,
                fileCollectionFactory,
                domainObjectCollectionFactory);
    }

    DomainObjectCollectionFactory createDomainObjectCollectionFactory(InstantiatorFactory instantiatorFactory, ServiceRegistry services) {
        return new DefaultDomainObjectCollectionFactory(instantiatorFactory, services, CollectionCallbackActionDecorator.NOOP, MutationGuards
                .identity());
    }

    ClassPathRegistry createClassPathRegistry(ModuleRegistry moduleRegistry, PluginModuleRegistry pluginModuleRegistry) {
        return new DefaultClassPathRegistry(
                new DefaultClassPathProvider(moduleRegistry),
                new DynamicModulesClassPathProvider(moduleRegistry,
                        pluginModuleRegistry));
    }

    DefaultModuleRegistry createModuleRegistry(CurrentGradleInstallation currentGradleInstallation) {
        return new DefaultModuleRegistry(additionalModuleClassPath, currentGradleInstallation.getInstallation());
    }

    CurrentGradleInstallation createCurrentGradleInstallation() {
        return CurrentGradleInstallation.locate();
    }

    PluginModuleRegistry createPluginModuleRegistry(ModuleRegistry moduleRegistry) {
        return new DefaultPluginModuleRegistry(moduleRegistry);
    }

    ClassLoaderRegistry createClassLoaderRegistry(ClassPathRegistry classPathRegistry, LegacyTypesSupport legacyTypesSupport) {
//        if (GradleRuntimeShadedJarDetector.isLoadedFrom(getClass())) {
//            return new FlatClassLoaderRegistry(getClass().getClassLoader());
//        }

        // Use DirectInstantiator here to avoid setting up the instantiation infrastructure early
        return new DefaultClassLoaderRegistry(classPathRegistry, legacyTypesSupport, DirectInstantiator.INSTANCE);
    }

    DefaultWorkValidationWarningRecorder createValidationWarningReporter() {
        return new DefaultWorkValidationWarningRecorder();
    }

    OverlappingOutputDetector createOverlappingOutputDetector() {
        return new DefaultOverlappingOutputDetector();
    }
}
