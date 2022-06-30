package com.tyron.builder.workers.internal;

import com.tyron.builder.api.file.ProjectLayout;
import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.initialization.ClassLoaderRegistry;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.state.ManagedFactoryRegistry;
import com.tyron.builder.internal.work.AsyncWorkTracker;
import com.tyron.builder.internal.work.ConditionalExecutionQueueFactory;
import com.tyron.builder.internal.work.DefaultConditionalExecutionQueueFactory;
import com.tyron.builder.internal.work.WorkerLeaseRegistry;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.process.internal.JavaForkOptionsFactory;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.health.memory.OsMemoryInfo;
import com.tyron.builder.process.internal.worker.WorkerProcessFactory;
import com.tyron.builder.process.internal.worker.child.DefaultWorkerDirectoryProvider;
import com.tyron.builder.process.internal.worker.child.WorkerDirectoryProvider;
import com.tyron.builder.workers.WorkerExecutor;

public class WorkersServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new GradleUserHomeServices());
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new BuildSessionScopeServices());
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.addProvider(new ProjectScopeServices());
        registration.add(IsolatedClassloaderWorkerFactory.class);
    }

    private static class BuildSessionScopeServices {
        WorkerDirectoryProvider createWorkerDirectoryProvider(GradleUserHomeDirProvider gradleUserHomeDirProvider) {
            return new DefaultWorkerDirectoryProvider(gradleUserHomeDirProvider);
        }

        ConditionalExecutionQueueFactory createConditionalExecutionQueueFactory(ExecutorFactory executorFactory, ParallelismConfiguration parallelismConfiguration, WorkerLeaseService workerLeaseService) {
            return new DefaultConditionalExecutionQueueFactory(parallelismConfiguration, executorFactory, workerLeaseService);
        }

        WorkerExecutionQueueFactory createWorkerExecutionQueueFactory(ConditionalExecutionQueueFactory conditionalExecutionQueueFactory) {
            return new WorkerExecutionQueueFactory(conditionalExecutionQueueFactory);
        }
    }

    private static class GradleUserHomeServices {
        WorkerDaemonClientsManager createWorkerDaemonClientsManager(WorkerProcessFactory workerFactory,
                                                                    LoggingManagerInternal loggingManager,
                                                                    ListenerManager listenerManager,
                                                                    MemoryManager memoryManager,
                                                                    OsMemoryInfo memoryInfo,
                                                                    ClassPathRegistry classPathRegistry,
                                                                    ActionExecutionSpecFactory actionExecutionSpecFactory) {
            return new WorkerDaemonClientsManager(new WorkerDaemonStarter(workerFactory, loggingManager, classPathRegistry, actionExecutionSpecFactory), listenerManager, loggingManager, memoryManager, memoryInfo);
        }

        ClassLoaderStructureProvider createClassLoaderStructureProvider(ClassLoaderRegistry classLoaderRegistry) {
            return new ClassLoaderStructureProvider(classLoaderRegistry);
        }

        IsolatableSerializerRegistry createIsolatableSerializerRegistry(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
            return new IsolatableSerializerRegistry(classLoaderHierarchyHasher, managedFactoryRegistry);
        }

        ActionExecutionSpecFactory createActionExecutionSpecFactory(IsolatableFactory isolatableFactory, IsolatableSerializerRegistry serializerRegistry) {
            return new DefaultActionExecutionSpecFactory(isolatableFactory, serializerRegistry);
        }
    }

    private static class ProjectScopeServices {
        WorkerExecutor createWorkerExecutor(InstantiatorFactory instantiatorFactory,
                                            WorkerDaemonFactory daemonWorkerFactory,
                                            IsolatedClassloaderWorkerFactory isolatedClassloaderWorkerFactory,
                                            JavaForkOptionsFactory forkOptionsFactory,
                                            WorkerLeaseRegistry workerLeaseRegistry,
                                            BuildOperationExecutor buildOperationExecutor,
                                            AsyncWorkTracker asyncWorkTracker,
                                            WorkerDirectoryProvider workerDirectoryProvider,
                                            ClassLoaderStructureProvider classLoaderStructureProvider,
                                            WorkerExecutionQueueFactory workerExecutionQueueFactory,
                                            ServiceRegistry projectServices,
                                            ActionExecutionSpecFactory actionExecutionSpecFactory,
                                            ProjectLayout projectLayout) {
            NoIsolationWorkerFactory noIsolationWorkerFactory = new NoIsolationWorkerFactory(buildOperationExecutor, instantiatorFactory, actionExecutionSpecFactory, projectServices);

            DefaultWorkerExecutor workerExecutor = instantiatorFactory.decorateLenient().newInstance(
                DefaultWorkerExecutor.class,
                daemonWorkerFactory,
                isolatedClassloaderWorkerFactory,
                noIsolationWorkerFactory,
                forkOptionsFactory,
                workerLeaseRegistry,
                buildOperationExecutor,
                asyncWorkTracker,
                workerDirectoryProvider,
                workerExecutionQueueFactory,
                classLoaderStructureProvider,
                actionExecutionSpecFactory,
                instantiatorFactory.decorateLenient(projectServices),
                projectLayout.getProjectDirectory().getAsFile());
            noIsolationWorkerFactory.setWorkerExecutor(workerExecutor);
            return workerExecutor;
        }

        WorkerDaemonFactory createWorkerDaemonFactory(WorkerDaemonClientsManager workerDaemonClientsManager, BuildOperationExecutor buildOperationExecutor) {
            return new WorkerDaemonFactory(workerDaemonClientsManager, buildOperationExecutor);
        }
    }
}
