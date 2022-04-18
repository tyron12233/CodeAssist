//package com.tyron.builder.api.execution.plan;
//
//import com.tyron.builder.api.Action;
//import com.tyron.builder.api.artifacts.component.BuildIdentifier;
//import com.tyron.builder.api.internal.TaskInternal;
//import com.tyron.builder.api.internal.project.ProjectInternal;
//import com.tyron.builder.api.internal.resources.ResourceLock;
//import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
//import com.tyron.builder.api.util.Path;
//import com.tyron.builder.composite.internal.IncludedBuildTaskResource;
//
//import java.util.Collections;
//import java.util.List;
//import java.util.Set;
//
//import javax.annotation.Nullable;
//
//public class TaskInAnotherBuild extends TaskNode implements SelfExecutingNode {
//
//    protected IncludedBuildTaskResource.State state = IncludedBuildTaskResource.State.Waiting;
//    private final Path taskIdentityPath;
//    private final String taskPath;
//    private final BuildIdentifier targetBuild;
//    private final IncludedBuildTaskResource target;
//
//    protected TaskInAnotherBuild(Path taskIdentityPath, String taskPath, BuildIdentifier targetBuild, IncludedBuildTaskResource target) {
//        this.taskIdentityPath = taskIdentityPath;
//        this.taskPath = taskPath;
//        this.targetBuild = targetBuild;
//        this.target = target;
//    }
//
//    public BuildIdentifier getTargetBuild() {
//        return targetBuild;
//    }
//
//    public String getTaskPath() {
//        return taskPath;
//    }
//
//    @Override
//    public TaskInternal getTask() {
//        return target.getTask();
//    }
//
//    @Override
//    public Set<Node> getLifecycleSuccessors() {
//        return Collections.emptySet();
//    }
//
//    @Override
//    public void setLifecycleSuccessors(Set<Node> successors) {
//        if (!successors.isEmpty()) {
//            throw new IllegalArgumentException();
//        }
//    }
//
//    @Override
//    public void prepareForExecution(Action<Node> monitor) {
//        target.queueForExecution();
//        target.onComplete(() -> monitor.execute(this));
//    }
//
//    @Nullable
//    @Override
//    public ResourceLock getProjectToLock() {
//        // Ignore, as the node in the other build's execution graph takes care of this
//        return null;
//    }
//
//    @Nullable
//    @Override
//    public ProjectInternal getOwningProject() {
//        // Ignore, as the node in the other build's execution graph takes care of this
//        return null;
//    }
//
//    @Override
//    public List<ResourceLock> getResourcesToLock() {
//        // Ignore, as the node in the other build's execution graph will take care of this
//        return Collections.emptyList();
//    }
//
//    @Override
//    public Throwable getNodeFailure() {
//        return null;
//    }
//
//    @Override
//    public void resolveDependencies(TaskDependencyResolver dependencyResolver) {
//    }
//
//    @Override
//    public boolean allDependenciesSuccessful() {
//        return super.allDependenciesSuccessful() && state == IncludedBuildTaskResource.State.Success;
//    }
//
//    @Override
//    public boolean doCheckDependenciesComplete() {
//        if (!super.doCheckDependenciesComplete()) {
//            return false;
//        }
//        // This node is ready to "execute" when the task in the other build has completed
//        if (state.isComplete()) {
//            return true;
//        }
//        state = target.getTaskState();
//        return state.isComplete();
//    }
//
//    @Override
//    public int compareTo(Node other) {
//        if (getClass() != other.getClass()) {
//            return getClass().getName().compareTo(other.getClass().getName());
//        }
//        TaskInAnotherBuild taskNode = (TaskInAnotherBuild) other;
//        return taskIdentityPath.compareTo(taskNode.taskIdentityPath);
//    }
//
//    @Override
//    public String toString() {
//        return taskIdentityPath.toString();
//    }
//
//    @Override
//    public void execute(NodeExecutionContext context) {
//        // This node does not do anything itself
//    }
//
//    private static BuildIdentifier buildIdentifierOf(TaskInternal task) {
//        return ((ProjectInternal) task.getProject()).getOwner().getOwner().getBuildIdentifier();
//    }
//}
