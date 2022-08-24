package org.gradle.execution.plan;


import org.gradle.api.Action;
import org.gradle.internal.build.ExecutionResult;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Responsible for running the work of a build tree, packaged as zero or more {@link ExecutionPlan} instances.
 */
@ServiceScope(Scopes.BuildTree.class)
@ThreadSafe
public interface PlanExecutor {
    /**
     * Executes a {@link WorkSource}, blocking until complete.
     *
     * @param workSource the work to execute.
     * @param worker the actual executor responsible to execute the nodes. Must be thread-safe.
     */
    <T> ExecutionResult<Void> process(WorkSource<T> workSource, Action<T> worker);

    /**
     * Verifies that this executor and the work it is running is healthy (not starved or deadlocked). Aborts any current work when not healthy, so that {@link #process(WorkSource, Action)}
     * returns with a failure result.
     *
     * <p>Note that this method is intended to be called periodically, but is not guaranteed to be particularly efficient, so should not be called too frequently (say more often than every 10 seconds).</p>
     */
    void assertHealthy();
}