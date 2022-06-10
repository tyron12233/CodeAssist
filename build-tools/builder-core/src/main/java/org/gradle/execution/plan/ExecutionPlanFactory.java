package org.gradle.execution.plan;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.Build.class)
public class ExecutionPlanFactory {
    private final String displayName;
    private final TaskNodeFactory taskNodeFactory;
    private final TaskDependencyResolver dependencyResolver;
    private final NodeValidator nodeValidator;
    private final ExecutionNodeAccessHierarchy outputHierarchy;
    private final ExecutionNodeAccessHierarchy destroyableHierarchy;

    public ExecutionPlanFactory(
            String displayName,
            TaskNodeFactory taskNodeFactory,
            TaskDependencyResolver dependencyResolver,
            NodeValidator nodeValidator,
            ExecutionNodeAccessHierarchy outputHierarchy,
            ExecutionNodeAccessHierarchy destroyableHierarchy
    ) {
        this.displayName = displayName;
        this.taskNodeFactory = taskNodeFactory;
        this.dependencyResolver = dependencyResolver;
        this.nodeValidator = nodeValidator;
        this.outputHierarchy = outputHierarchy;
        this.destroyableHierarchy = destroyableHierarchy;
    }

    public ExecutionPlan createPlan() {
        return new DefaultExecutionPlan(displayName, taskNodeFactory, dependencyResolver, nodeValidator, outputHierarchy, destroyableHierarchy);
    }
}
