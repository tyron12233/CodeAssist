package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.specs.Spec;
import org.gradle.internal.Factory;
import org.gradle.internal.logging.StandardOutputCapture;
import org.gradle.api.internal.project.taskfactory.TaskIdentity;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.util.Path;

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
    Spec<? super TaskInternal> getOnlyIf();

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
     * @see org.gradle.api.tasks.UntrackedTask
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
