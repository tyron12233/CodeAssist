package com.tyron.builder.api.internal.service.scopes;

import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.service.ServiceRegistry;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;

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
        });
    }

    ExecutionNodeAccessHierarchies createExecutionNodeAccessHierarchies(FileSystem fileSystem, Stat stat) {
        return new ExecutionNodeAccessHierarchies(fileSystem.isCaseSensitive() ? CaseSensitivity.CASE_SENSITIVE : CaseSensitivity.CASE_INSENSITIVE, stat);
    }

//    protected TaskStatistics createTaskStatistics() {
//        return new TaskStatistics();
//    }
}
