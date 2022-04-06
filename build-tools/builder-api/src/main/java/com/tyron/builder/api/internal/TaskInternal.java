package com.tyron.builder.api.internal;

import com.tyron.builder.api.GradleEnterprisePluginManager;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.logging.StandardOutputCapture;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskOutputsInternal;
import com.tyron.builder.api.tasks.TaskState;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public interface TaskInternal extends Task {

    /**
     * A more efficient version of {@link #getActions()}, which circumvents the
     * validating change listener that normally prevents users from changing tasks
     * once they start executing.
     */
    List<InputChangesAwareTaskAction> getTaskActions();

    /**
     * "Lifecycle dependencies" are dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)} call,
     * as opposed to the recommended approach of connecting producer tasks' outputs to consumer tasks' inputs.
     * @return the dependencies of this task declared via an explicit {@link Task#dependsOn(Object...)}
     */
    TaskDependency getLifecycleDependencies();

    @Internal
    Predicate<? super TaskInternal> getOnlyIf();

    /**
     * <p>Gets the shared resources required by this task.</p>
     */
    @Internal
    List<? extends ResourceLock> getSharedResources();

    TaskOutputsInternal getOutputs();

    TaskInputsInternal getInputs();

    TaskStateInternal getState();

    boolean getImpliesSubProjects();

    @Internal
    StandardOutputCapture getStandardOutputCapture();

    /**
     * Return the reason for not to track state.
     *
     * Gradle considers the task as untracked if the reason is present.
     * When not tracking state, a reason must be present. Hence the {@code Optional} represents the state of enablement, too.
     *
     * @see org.gradle.api.tasks.UntrackedTask
     */
    @Internal
    default Optional<String> getReasonNotToTrackState() {
        return Optional.empty();
    }
}
