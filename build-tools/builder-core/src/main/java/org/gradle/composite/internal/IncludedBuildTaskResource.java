package org.gradle.composite.internal;

import org.gradle.execution.plan.Node;
import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.buildtree.BuildTreeWorkGraph;

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
     * Queues a task for execution, but does not schedule it. Use {@link org.gradle.internal.buildtree.BuildTreeWorkGraph#scheduleWork(Consumer)} to schedule queued tasks.
     */
    void queueForExecution();

    TaskInternal getTask();

    State getTaskState();
}
