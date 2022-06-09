package com.tyron.builder.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.composite.internal.BuildTreeWorkGraphController;
import com.tyron.builder.composite.internal.IncludedBuildTaskResource;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.resources.ResourceLock;
import com.tyron.builder.util.Path;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class TaskInAnotherBuild extends TaskNode {
    public static TaskInAnotherBuild of(
        TaskInternal task,
        BuildTreeWorkGraphController taskGraph
    ) {
        BuildIdentifier targetBuild = buildIdentifierOf(task);
        IncludedBuildTaskResource taskResource = taskGraph.locateTask(targetBuild, task);
        return new TaskInAnotherBuild(task.getIdentityPath(), task.getPath(), targetBuild, taskResource);
    }

    public static TaskInAnotherBuild of(
        String taskPath,
        BuildIdentifier targetBuild,
        BuildTreeWorkGraphController taskGraph
    ) {
        IncludedBuildTaskResource taskResource = taskGraph.locateTask(targetBuild, taskPath);
        Path taskIdentityPath = Path.path(targetBuild.getName()).append(Path.path(taskPath));
        return new TaskInAnotherBuild(taskIdentityPath, taskPath, targetBuild, taskResource);
    }

    protected IncludedBuildTaskResource.State state = IncludedBuildTaskResource.State.Waiting;
    private final Path taskIdentityPath;
    private final String taskPath;
    private final BuildIdentifier targetBuild;
    private final IncludedBuildTaskResource target;

    protected TaskInAnotherBuild(Path taskIdentityPath, String taskPath, BuildIdentifier targetBuild, IncludedBuildTaskResource target) {
        this.taskIdentityPath = taskIdentityPath;
        this.taskPath = taskPath;
        this.targetBuild = targetBuild;
        this.target = target;
        doNotRequire();
    }

    public BuildIdentifier getTargetBuild() {
        return targetBuild;
    }

    public String getTaskPath() {
        return taskPath;
    }

    @Override
    public TaskInternal getTask() {
        return target.getTask();
    }

    @Override
    public void prepareForExecution() {
        target.queueForExecution();
    }

    @Nullable
    @Override
    public ResourceLock getProjectToLock() {
        // Ignore, as the node in the other build's execution graph takes care of this
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal getOwningProject() {
        // Ignore, as the node in the other build's execution graph takes care of this
        return null;
    }

    @Override
    public List<ResourceLock> getResourcesToLock() {
        // Ignore, as the node in the other build's execution graph will take care of this
        return Collections.emptyList();
    }

    @Override
    public Throwable getNodeFailure() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rethrowNodeFailure() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void appendPostAction(Action<? super Task> action) {
        // Ignore. Currently, the actions don't need to run, it's just better if they do
        // By the time this node is notified that the task in the other build has completed, it's too late to run the action
        // Instead, the action should be attached to the task in the other build rather than here
    }

    @Override
    public Action<? super Task> getPostAction() {
        return Actions.doNothing();
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
    }

    @Override
    public boolean requiresMonitoring() {
        return true;
    }

    @Override
    public boolean isSuccessful() {
        return state == IncludedBuildTaskResource.State.Success;
    }

    @Override
    public boolean isVerificationFailure() {
        return false;
    }

    @Override
    public boolean isFailed() {
        return state == IncludedBuildTaskResource.State.Failed;
    }

    @Override
    public boolean isComplete() {
        if (super.isComplete() || state.isComplete()) {
            return true;
        }

        state = target.getTaskState();
        return state.isComplete();
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        TaskInAnotherBuild taskNode = (TaskInAnotherBuild) other;
        return taskIdentityPath.compareTo(taskNode.taskIdentityPath);
    }

    @Override
    public String toString() {
        return taskIdentityPath.toString();
    }

    @Override
    public void resolveMutations() {
        // Assume for now that no task in the consuming build will destroy the outputs of this task or overlaps with this task
    }

    private static BuildIdentifier buildIdentifierOf(TaskInternal task) {
        return ((ProjectInternal) task.getProject()).getOwner().getOwner().getBuildIdentifier();
    }
}
