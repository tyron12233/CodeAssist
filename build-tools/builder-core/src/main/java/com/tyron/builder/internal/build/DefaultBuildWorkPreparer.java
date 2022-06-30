package com.tyron.builder.internal.build;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.execution.plan.ExecutionPlanFactory;

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
        taskGraph.populate(plan);
        BuildOutputCleanupRegistry buildOutputCleanupRegistry = gradle.getServices().get(BuildOutputCleanupRegistry.class);
        buildOutputCleanupRegistry.resolveOutputs();
    }
}