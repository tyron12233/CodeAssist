package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;
import com.tyron.builder.api.internal.work.WorkerLeaseService;

public class BuildScopeServices extends DefaultServiceRegistry {

    public BuildScopeServices(ServiceRegistry parent) {
        super(parent);
        register(registration -> {
            registration.add(ProjectFactory.class);
            registration.add(DefaultNodeValidator.class);
            registration.add(TaskNodeFactory.class);
            registration.add(TaskNodeDependencyResolver.class);
//            registration.add(WorkNodeDependencyResolver.class);
            registration.add(TaskDependencyResolver.class);

            registration.add(DefaultResourceLockCoordinationService.class);
            registration.add(DefaultWorkerLeaseService.class);
        });
    }

    PlanExecutor createPlanExecutor(
            ExecutorFactory factory,
            WorkerLeaseService service,
            ResourceLockCoordinationService resourceLockService
    ) {
        return new DefaultPlanExecutor(
            factory,
            service,
            new BuildCancellationToken() {

                @Override
                public boolean isCancellationRequested() {
                    return false;
                }

                @Override
                public void cancel() {

                }

                @Override
                public boolean addCallback(Runnable cancellationHandler) {
                    return false;
                }

                @Override
                public void removeCallback(Runnable cancellationHandler) {

                }
            },
            resourceLockService
        );
    }

    ExecutorFactory createExecutorFactory() {
        return new DefaultExecutorFactory();
    }

    ExecutionNodeAccessHierarchies createExecutionNodeAccessHierarchies() {
        return new ExecutionNodeAccessHierarchies(CaseSensitivity.CASE_INSENSITIVE, FileSystems.getDefault());
    }

//    protected TaskStatistics createTaskStatistics() {
//        return new TaskStatistics();
//    }
}
