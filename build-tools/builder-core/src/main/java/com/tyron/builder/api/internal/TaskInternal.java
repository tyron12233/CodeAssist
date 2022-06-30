package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.internal.TaskOutputsInternal;
import com.tyron.builder.api.tasks.TaskState;
import com.tyron.builder.util.Path;

import java.io.File;
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

    @Internal
    boolean hasTaskActions();

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

    @Override
    TaskOutputsInternal getOutputs();

    @Override
    TaskInputsInternal getInputs();

    @Override
    TaskStateInternal getState();

    @Internal
    boolean getImpliesSubProjects();

    void setImpliesSubProjects(boolean impliesSubProjects);

    /**
     * The returned factory is expected to return the same file each time.
     * <p>
     * The getTemporaryDir() method creates the directory which can be problematic. Use this to delay that creation.
     */
    @Internal
    Factory<File> getTemporaryDirFactory();

    @Internal
    StandardOutputCapture getStandardOutputCapture();

    /**
     * Return the reason for not to track state.
     *
     * Gradle considers the task as untracked if the reason is present.
     * When not tracking state, a reason must be present. Hence the {@code Optional} represents the state of enablement, too.
     *
     * @see com.tyron.builder.api.tasks.UntrackedTask
     */
    @Internal
    default Optional<String> getReasonNotToTrackState() {
        return Optional.empty();
    }

    @Internal
    TaskIdentity<?> getTaskIdentity();

    void prependParallelSafeAction(Action<? super Task> action);

    void appendParallelSafeAction(Action<? super Task> action);

    @Internal
    boolean isHasCustomActions();

    @Internal
    Path getIdentityPath();
}
