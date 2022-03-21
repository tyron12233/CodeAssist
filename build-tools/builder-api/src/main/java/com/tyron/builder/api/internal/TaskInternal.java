package com.tyron.builder.api.internal;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskOutputsInternal;

import java.util.List;

public interface TaskInternal extends Task {

    /**
     * "Lifecycle dependencies" are dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)} call,
     * as opposed to the recommended approach of connecting producer tasks' outputs to consumer tasks' inputs.
     * @return the dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)}
     */
    TaskDependency getLifecycleDependencies();

    /**
     * <p>Gets the shared resources required by this task.</p>
     */
    List<? extends ResourceLock> getSharedResources();

    TaskOutputsInternal getOutputs();
}
