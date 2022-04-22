package com.tyron.builder.execution.plan;


import com.tyron.builder.execution.plan.DefaultExecutionPlan;
import com.tyron.builder.execution.plan.ExecutionNodeAccessHierarchy;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.execution.plan.TaskDependencyResolver;
import com.tyron.builder.execution.plan.TaskNodeFactory;
import com.tyron.builder.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.Build.class)
public class ExecutionPlanFactory {
    private final String displayName;
    private final TaskNodeFactory taskNodeFactory;
    private final TaskDependencyResolver dependencyResolver;
    private final ExecutionNodeAccessHierarchy outputHierarchy;
    private final ExecutionNodeAccessHierarchy destroyableHierarchy;
    private final ResourceLockCoordinationService lockCoordinationService;

    public ExecutionPlanFactory(
            String displayName,
            TaskNodeFactory taskNodeFactory,
            TaskDependencyResolver dependencyResolver,
            ExecutionNodeAccessHierarchy outputHierarchy,
            ExecutionNodeAccessHierarchy destroyableHierarchy,
            ResourceLockCoordinationService lockCoordinationService
    ) {
        this.displayName = displayName;
        this.taskNodeFactory = taskNodeFactory;
        this.dependencyResolver = dependencyResolver;
        this.outputHierarchy = outputHierarchy;
        this.destroyableHierarchy = destroyableHierarchy;
        this.lockCoordinationService = lockCoordinationService;
    }

    public ExecutionPlan createPlan() {
        return new DefaultExecutionPlan(displayName, taskNodeFactory, dependencyResolver, outputHierarchy, destroyableHierarchy, lockCoordinationService);
    }
}