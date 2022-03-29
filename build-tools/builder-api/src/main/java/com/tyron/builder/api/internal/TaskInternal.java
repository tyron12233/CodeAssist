package com.tyron.builder.api.internal;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskOutputsInternal;
import com.tyron.builder.api.tasks.TaskState;

import java.util.List;
import java.util.function.Predicate;

public interface TaskInternal extends Task {

    /**
     * "Lifecycle dependencies" are dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)} call,
     * as opposed to the recommended approach of connecting producer tasks' outputs to consumer tasks' inputs.
     * @return the dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)}
     */
    TaskDependency getLifecycleDependencies();

    Predicate<? super TaskInternal> getOnlyIf();

    /**
     * <p>Gets the shared resources required by this task.</p>
     */
    List<? extends ResourceLock> getSharedResources();

    TaskOutputsInternal getOutputs();

    TaskInputsInternal getInputs();

    TaskStateInternal getState();

    boolean getImpliesSubProjects();
}
