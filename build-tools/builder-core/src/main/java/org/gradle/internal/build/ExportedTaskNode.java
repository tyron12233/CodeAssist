package org.gradle.internal.build;

import org.gradle.api.internal.TaskInternal;
import org.gradle.composite.internal.IncludedBuildTaskResource;
import org.gradle.execution.plan.Node;

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

    String healthDiagnostics();
}