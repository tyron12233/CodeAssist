package org.gradle.internal.build;

import org.gradle.api.internal.GradleInternal;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.execution.plan.ExecutionPlanFactory;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;

import java.util.function.Consumer;

public class DefaultBuildWorkPreparer implements BuildWorkPreparer {
    private final ExecutionPlanFactory executionPlanFactory;

    public DefaultBuildWorkPreparer(ExecutionPlanFactory executionPlanFactory) {
        this.executionPlanFactory = executionPlanFactory;
    }

    @Override
    public ExecutionPlan newExecutionPlan() {
        return executionPlanFactory.createPlan();
    }

    @Override
    public void populateWorkGraph(GradleInternal gradle, ExecutionPlan plan, Consumer<? super ExecutionPlan> action) {
        action.accept(plan);
        plan.determineExecutionPlan();
    }

    @Override
    public void finalizeWorkGraph(GradleInternal gradle, ExecutionPlan plan) {
        TaskExecutionGraphInternal taskGraph = gradle.getTaskGraph();
        plan.finalizePlan();
        taskGraph.populate(plan);
        BuildOutputCleanupRegistry buildOutputCleanupRegistry = gradle.getServices().get(BuildOutputCleanupRegistry.class);
        buildOutputCleanupRegistry.resolveOutputs();
    }
}