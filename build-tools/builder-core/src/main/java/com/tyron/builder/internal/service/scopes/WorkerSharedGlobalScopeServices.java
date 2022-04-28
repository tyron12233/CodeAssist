package com.tyron.builder.internal.service.scopes;

import static com.tyron.builder.api.internal.provider.ManagedFactories.*;

import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.provider.ManagedFactories;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.hash.DefaultStreamHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.progress.DefaultProgressLoggerFactory;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.logging.services.ProgressLoggingBridge;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.DefaultBuildOperationIdFactory;
import com.tyron.builder.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.time.Time;

public class WorkerSharedGlobalScopeServices extends BasicGlobalScopeServices {

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


    TaskDependencyFactory createTaskDependencyFactory() {
        return DefaultTaskDependencyFactory.withNoAssociatedProject();
    }

    DefaultFilePropertyFactory createFilePropertyFactory(PropertyHost propertyHost, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory) {
        return new DefaultFilePropertyFactory(propertyHost, fileResolver, fileCollectionFactory);
    }

    StreamHasher createStreamHasher() {
        return new DefaultStreamHasher();
    }

//    ManagedFactoryRegistry createManagedFactoryRegistry(NamedObjectInstantiator namedObjectInstantiator, InstantiatorFactory instantiatorFactory, PropertyFactory propertyFactory, FileCollectionFactory fileCollectionFactory, FileFactory fileFactory, FilePropertyFactory filePropertyFactory) {
//        return new DefaultManagedFactoryRegistry().withFactories(
//                instantiatorFactory.getManagedFactory(),
////                new ConfigurableFileCollectionManagedFactory(fileCollectionFactory),
//                new com.tyron.builder.api.internal.file.ManagedFactories.RegularFileManagedFactory(fileFactory),
//                new com.tyron.builder.api.internal.file.ManagedFactories.RegularFilePropertyManagedFactory(filePropertyFactory),
//                new com.tyron.builder.api.internal.file.ManagedFactories.DirectoryManagedFactory(fileFactory),
//                new com.tyron.builder.api.internal.file.ManagedFactories.DirectoryPropertyManagedFactory(filePropertyFactory),
//                new SetPropertyManagedFactory(propertyFactory),
//                new ListPropertyManagedFactory(propertyFactory),
//                new MapPropertyManagedFactory(propertyFactory),
//                new PropertyManagedFactory(propertyFactory),
//                new ProviderManagedFactory(),
//                namedObjectInstantiator
//        );
//    }
}
