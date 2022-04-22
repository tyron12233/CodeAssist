package com.tyron.builder.internal.build;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;

import java.util.function.Consumer;

public interface BuildWorkPreparer {
    /**
     * Creates a new, empty plan.
     */
    ExecutionPlan newExecutionPlan();

    /**
     * Populates the given execution plan using the given action.
     */
    void populateWorkGraph(GradleInternal gradle, ExecutionPlan plan, Consumer<? super ExecutionPlan> action);

    /**
     * Finalises the given execution plan once all work has been scheduled.
     */
    void finalizeWorkGraph(GradleInternal gradle, ExecutionPlan plan);
}