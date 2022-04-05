package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.LocalTaskNodeExecutor;
import com.tyron.builder.api.execution.plan.NodeExecutor;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.service.scopes.ExecutionGradleServices;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.api.work.AsyncWorkTracker;
import com.tyron.builder.api.work.DefaultAsyncWorkTracker;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.internal.CacheFactory;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.DefaultCacheFactory;
import com.tyron.builder.cache.internal.DefaultCacheRepository;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;
import com.tyron.builder.cache.internal.scopes.DefaultBuildScopedCache;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.common.TestUtil;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class GradleScopeServices extends DefaultServiceRegistry {

    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent) {
        super(parent);
        register(registration -> {
//            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
//                pluginServiceRegistry.registerGradleServices(registration);
//            }
            registration.add(ProjectFactory.class);
        });
    }

    AsyncWorkTracker createAsyncWorkTracker(
            WorkerLeaseService workerLeaseService
    ) {
        return new DefaultAsyncWorkTracker(workerLeaseService);
    }

    LocalTaskNodeExecutor createLocalTaskNodeExecutor(ExecutionNodeAccessHierarchies executionNodeAccessHierarchies) {
        return new LocalTaskNodeExecutor(
                executionNodeAccessHierarchies.getOutputHierarchy()
        );
    }

//    WorkNodeExecutor createWorkNodeExecutor() {
//        return new WorkNodeExecutor();
//    }

    TaskExecutionGraphInternal createTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors,
            GradleInternal gradle,
            ServiceRegistry gradleScopedServices
    ) {
        return new DefaultTaskExecutionGraph(
                planExecutor,
                nodeExecutors,
                gradle,
                gradleScopedServices
        );
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
//        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            @Override
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
