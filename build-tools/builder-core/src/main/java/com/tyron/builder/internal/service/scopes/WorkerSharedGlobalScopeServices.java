package com.tyron.builder.internal.service.scopes;

import static com.tyron.builder.api.internal.provider.ManagedFactories.*;

import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import com.tyron.builder.initialization.DefaultLegacyTypesSupport;
import com.tyron.builder.initialization.LegacyTypesSupport;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.impl.DefaultDeleter;
import com.tyron.builder.internal.hash.DefaultStreamHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.progress.DefaultProgressLoggerFactory;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.logging.services.ProgressLoggingBridge;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.DefaultBuildOperationIdFactory;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.internal.state.DefaultManagedFactoryRegistry;
import com.tyron.builder.internal.state.ManagedFactoryRegistry;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.time.Time;

@SuppressWarnings({"unused"})
public class WorkerSharedGlobalScopeServices extends BasicGlobalScopeServices {

    LegacyTypesSupport createLegacyTypesSupport() {
        return new DefaultLegacyTypesSupport();
    }

    BuildOperationIdFactory createBuildOperationIdProvider() {
        return new DefaultBuildOperationIdFactory();
    }

    ProgressLoggerFactory createProgressLoggerFactory(OutputEventListener outputEventListener, Clock clock, BuildOperationIdFactory buildOperationIdFactory) {
        return new DefaultProgressLoggerFactory(new ProgressLoggingBridge(outputEventListener), clock, buildOperationIdFactory);
    }

    Clock createClock() {
        return Time.clock();
    }

    CrossBuildInMemoryCacheFactory createCrossBuildInMemoryCacheFactory(ListenerManager listenerManager) {
        return new DefaultCrossBuildInMemoryCacheFactory(listenerManager);
    }

    NamedObjectInstantiator createNamedObjectInstantiator(CrossBuildInMemoryCacheFactory cacheFactory) {
        return new NamedObjectInstantiator(cacheFactory);
    }

    TaskDependencyFactory createTaskDependencyFactory() {
        return DefaultTaskDependencyFactory.withNoAssociatedProject();
    }

    DefaultFilePropertyFactory createFilePropertyFactory(PropertyHost propertyHost, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory) {
        return new DefaultFilePropertyFactory(propertyHost, fileResolver, fileCollectionFactory);
    }

    Deleter createDeleter(Clock clock, FileSystem fileSystem, OperatingSystem os) {
        return new DefaultDeleter(clock::getCurrentTime, fileSystem::isSymlink, os.isWindows());
    }

    StreamHasher createStreamHasher() {
        return new DefaultStreamHasher();
    }

    PropertyFactory createPropertyFactory(PropertyHost propertyHost) {
        return new DefaultPropertyFactory(propertyHost);
    }

    ManagedFactoryRegistry createManagedFactoryRegistry(InstantiatorFactory instantiatorFactory, PropertyFactory propertyFactory, FileCollectionFactory fileCollectionFactory, FileFactory fileFactory, FilePropertyFactory filePropertyFactory) {
        return new DefaultManagedFactoryRegistry().withFactories(
//                instantiatorFactory.getManagedFactory(),
//                new ConfigurableFileCollectionManagedFactory(fileCollectionFactory),
                new com.tyron.builder.api.internal.file.ManagedFactories.RegularFileManagedFactory(fileFactory),
                new com.tyron.builder.api.internal.file.ManagedFactories.RegularFilePropertyManagedFactory(filePropertyFactory),
                new com.tyron.builder.api.internal.file.ManagedFactories.DirectoryManagedFactory(fileFactory),
                new com.tyron.builder.api.internal.file.ManagedFactories.DirectoryPropertyManagedFactory(filePropertyFactory),
                new SetPropertyManagedFactory(propertyFactory),
                new ListPropertyManagedFactory(propertyFactory),
                new MapPropertyManagedFactory(propertyFactory),
                new PropertyManagedFactory(propertyFactory),
                new ProviderManagedFactory()
        );
    }
}
