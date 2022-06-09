package com.tyron.builder.composite.internal;

import com.tyron.builder.execution.plan.Node;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.buildtree.BuildTreeWorkGraph;

import java.util.function.Consumer;

/**
 * A resource produced by a task in an included build.
 */
public interface IncludedBuildTaskResource {
    enum State {
        Waiting(false), Success(true), Failed(true);

        private final boolean complete;

        State(boolean complete) {
            this.complete = complete;
        }

        public boolean isComplete() {
            return complete;
        }
    }

    /**
     * Queues a task for execution, but does not schedule it. Use {@link com.tyron.builder.internal.buildtree.BuildTreeWorkGraph#scheduleWork(Consumer)} to schedule queued tasks.
     */
    void queueForExecution();

    TaskInternal getTask();

    State getTaskState();
}
