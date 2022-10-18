package org.gradle.execution.plan;

import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.Build.class)
public class ExecutionPlanFactory {
    private final String displayName;
    private final TaskNodeFactory taskNodeFactory;
    private final OrdinalGroupFactory ordinalGroupFactory;
    private final TaskDependencyResolver dependencyResolver;
    private final ExecutionNodeAccessHierarchy outputHierarchy;
    private final ExecutionNodeAccessHierarchy destroyableHierarchy;
    private final ResourceLockCoordinationService lockCoordinationService;

    public ExecutionPlanFactory(
            String displayName,
            TaskNodeFactory taskNodeFactory,
            OrdinalGroupFactory ordinalGroupFactory,
            TaskDependencyResolver dependencyResolver,
            ExecutionNodeAccessHierarchy outputHierarchy,
            ExecutionNodeAccessHierarchy destroyableHierarchy,
            ResourceLockCoordinationService lockCoordinationService
    ) {
        this.displayName = displayName;
        this.taskNodeFactory = taskNodeFactory;
        this.ordinalGroupFactory = ordinalGroupFactory;
        this.dependencyResolver = dependencyResolver;
        this.outputHierarchy = outputHierarchy;
        this.destroyableHierarchy = destroyableHierarchy;
        this.lockCoordinationService = lockCoordinationService;
    }

    public ExecutionPlan createPlan() {
        return new DefaultExecutionPlan(displayName, taskNodeFactory, ordinalGroupFactory, dependencyResolver, outputHierarchy, destroyableHierarchy, lockCoordinationService);
    }
}