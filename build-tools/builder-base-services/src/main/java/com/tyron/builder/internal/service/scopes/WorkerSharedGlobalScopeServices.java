package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.file.impl.DefaultDeleter;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.hash.DefaultStreamHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.DefaultBuildOperationIdFactory;
import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.internal.reflect.service.scopes.BasicGlobalScopeServices;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import com.tyron.builder.internal.logging.progress.DefaultProgressLoggerFactory;
import com.tyron.builder.internal.logging.services.ProgressLoggingBridge;

public class WorkerSharedGlobalScopeServices extends BasicGlobalScopeServices {

    Clock createClock() {
        return Time.clock();
    }

    Deleter createDeleter() {
        return new DefaultDeleter();
    }

    StreamHasher createStreamHasher() {
        return new DefaultStreamHasher();
    }

    PropertyFactory createPropertyFactory(PropertyHost propertyHost) {
        return new DefaultPropertyFactory(propertyHost);
    }

    ProgressLoggerFactory createProgressLoggerFactory(
            OutputEventListener outputEventListener,
            Clock clock,
            BuildOperationIdFactory buildOperationIdFactory
    ) {
        return new DefaultProgressLoggerFactory(new ProgressLoggingBridge(outputEventListener), clock, buildOperationIdFactory);
    }

    CrossBuildInMemoryCacheFactory createCrossBuildInMemoryFactory(
            ListenerManager listenerManager
    ) {
        return new DefaultCrossBuildInMemoryCacheFactory(listenerManager);
    }

    BuildOperationIdFactory createBuildOperationFactory() {
        return new DefaultBuildOperationIdFactory();
    }


    TaskDependencyFactory createTaskDependencyFactory() {
        return DefaultTaskDependencyFactory.withNoAssociatedProject();
    }
}
