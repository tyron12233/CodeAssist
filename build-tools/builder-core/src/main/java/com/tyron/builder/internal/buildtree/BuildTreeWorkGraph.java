package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.ExecutionResult;

import java.util.function.Consumer;

/**
 * Represents a set of work to be executed across a build tree.
 */
public interface BuildTreeWorkGraph {
    /**
     * Schedules work using the given action and then prepares this work graphs for execution. Does not run any work until {@link #runWork()} is called.
     *
     * <p>This can be called only once for a given graph.</p>
     */
    void scheduleWork(Consumer<? super Builder> action);

    /**
     * Runs any scheduled work, blocking until complete. Does nothing when {@link #scheduleWork(Consumer)} has not been called to schedule the work.
     *
     * <p>This can be called only once for a given graph.</p>
     */
    ExecutionResult<Void> runWork();

    interface Builder {
        /**
         * Adds nodes to the work graph for the given build.
         */
        void withWorkGraph(BuildState target, Consumer<? super BuildLifecycleController.WorkGraphBuilder> action);
    }
}