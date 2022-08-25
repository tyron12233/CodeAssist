package org.gradle.execution.plan;

import org.gradle.internal.concurrent.Stoppable;

import java.util.function.Consumer;

public interface BuildWorkPlan extends Stoppable {
    /**
     * Invokes the given action when a task completes (as per {@link Node#isComplete()}). Does nothing for tasks that have already completed.
     */
    void onComplete(Consumer<LocalTaskNode> handler);
}