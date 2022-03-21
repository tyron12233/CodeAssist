package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.internal.resources.ResourceLock;

public interface ProjectState {

    /**
     * Returns the lock that should be acquired by non-isolated tasks from this project prior to execution.
     *
     * <p>This lock allows both access to the project state and the right to execute as a task. Acquiring this lock also acquires the lock returned by {@link #getAccessLock()}.
     *
     * <p>When a task reaches across project boundaries, the project state lock is released but the task execution lock is not. This prevents other tasks from the same project from starting but
     * allows tasks in other projects to access the state of this project without deadlocks in cases where there are dependency cycles between projects. It also allows other non-taask work
     * to run while the task is blocked.
     *
     * <p>When parallel execution is not enabled, the lock is shared between projects within a build, and each build in the build tree has its own shared lock.
     */
    ResourceLock getTaskExecutionLock();
}
