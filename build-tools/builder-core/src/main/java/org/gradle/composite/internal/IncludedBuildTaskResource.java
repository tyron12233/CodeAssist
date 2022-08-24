package org.gradle.composite.internal;

import org.gradle.api.internal.TaskInternal;
import org.gradle.execution.plan.Node;

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
     * Queues the task for execution, but does not schedule it. Use {@link org.gradle.internal.buildtree.BuildTreeWorkGraph#scheduleWork(Consumer)} to schedule queued tasks.
     */
    void queueForExecution();

    /**
     * Invokes the given action when this task completes (as per {@link Node#isComplete()}). Does nothing if this task has already completed.
     */
    void onComplete(Runnable action);

    TaskInternal getTask();

    State getTaskState();

    String healthDiagnostics();
}
