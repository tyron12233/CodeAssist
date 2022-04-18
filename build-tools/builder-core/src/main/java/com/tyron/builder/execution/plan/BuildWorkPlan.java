package com.tyron.builder.execution.plan;


import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.execution.plan.Node;
import com.tyron.builder.internal.concurrent.Stoppable;

import java.util.function.Consumer;

public interface BuildWorkPlan extends Stoppable {
    /**
     * Invokes the given action when a task completes (as per {@link Node#isComplete()}). Does nothing for tasks that have already completed.
     */
    void onComplete(Consumer<LocalTaskNode> handler);
}