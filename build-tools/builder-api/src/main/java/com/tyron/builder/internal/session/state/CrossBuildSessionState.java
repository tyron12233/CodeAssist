package com.tyron.builder.internal.session.state;


import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.api.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationExecutor;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationQueueFactory;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistryBuilder;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.service.scopes.ServiceScope;
import com.tyron.builder.api.internal.time.Clock;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.internal.concurrent.DefaultParallelismConfiguration;

import java.io.Closeable;

/**
 * Services to be shared across build sessions.
 *
 * Generally, one regular Gradle invocation is conceptually a session.
 * However, the GradleBuild task is currently implemented in such a way that it uses a discrete session.
 * Having the GradleBuild task reuse the outer session is complicated because it may use a different Gradle user home.
 * See https://github.com/gradle/gradle/issues/4559.
 *
 * This set of services is added as a parent of each build session scope.
 */
@ServiceScope(Scopes.BuildSession.class)
public class CrossBuildSessionState implements Closeable {

    private final ServiceRegistry services;

    public CrossBuildSessionState(ServiceRegistry parent, StartParameter startParameter) {
        this.services = ServiceRegistryBuilder.builder()
                .displayName("cross session services")
                .parent(parent)
                .provider(new Services(startParameter))
                .build();
        // Trigger listener to wire itself in
//        services.get(BuildOperationTrace.class);
    }

    public ServiceRegistry getServices() {
        return services;
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(services).stop();
    }

    private class Services {
        private final StartParameter startParameter;

        public Services(StartParameter startParameter) {
            this.startParameter = startParameter;
        }

        void configure(ServiceRegistration registration) {
            registration.add(DefaultResourceLockCoordinationService.class);
            registration.add(DefaultWorkerLeaseService.class);
        }

        CrossBuildSessionState createCrossBuildSessionState() {
            return CrossBuildSessionState.this;
        }

        ParallelismConfiguration createParallelismConfiguration() {
            return new DefaultParallelismConfiguration(startParameter.isParallelProjectExecutionEnabled(), startParameter.getMaxWorkerCount());
        }

        BuildOperationExecutor createBuildOperationExecutor(
                Clock clock,
                ProgressLoggerFactory progressLoggerFactory,
                WorkerLeaseService workerLeaseService,
                ExecutorFactory executorFactory,
                ParallelismConfiguration parallelismConfiguration,
                BuildOperationIdFactory buildOperationIdFactory,
                BuildOperationListenerManager buildOperationListenerManager
        ) {
            return new DefaultBuildOperationExecutor(
                    buildOperationListenerManager.getBroadcaster(),
                    clock,
                    progressLoggerFactory,
                    new DefaultBuildOperationQueueFactory(workerLeaseService),
                    executorFactory,
                    parallelismConfiguration,
                    buildOperationIdFactory
            );
        }


    }
}