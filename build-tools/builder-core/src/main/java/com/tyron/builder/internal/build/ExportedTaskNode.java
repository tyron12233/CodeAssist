package com.tyron.builder.internal.build;

import com.tyron.builder.execution.plan.Node;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.composite.internal.IncludedBuildTaskResource;

/**
 * A node in a build's work graph that can be referenced by the work graph of another build.
 */
public interface ExportedTaskNode {
    TaskInternal getTask();

    IncludedBuildTaskResource.State getTaskState();

    /**
     * Invokes the given action when this task completes (as per {@link Node#isComplete()}). Does nothing if this task has already completed.
     */
    void onComplete(Runnable action);
}