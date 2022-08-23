package org.gradle.execution.plan;

import org.gradle.api.Action;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.WorkNodeAction;
import org.gradle.internal.resources.ResourceLock;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ActionNode extends Node implements SelfExecutingNode {
    private final WorkNodeAction action;
    private final ProjectInternal owningProject;
    private final ProjectInternal projectToLock;

    public ActionNode(WorkNodeAction action) {
        this.action = action;
        this.owningProject = (ProjectInternal) action.getOwningProject();
        if (owningProject != null && action.usesMutableProjectState()) {
            this.projectToLock = owningProject;
        } else {
            this.projectToLock = null;
        }
    }

    @Nullable
    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void rethrowNodeFailure() {
    }

    @Override
    public void prepareForExecution() {
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        TaskDependencyContainer dependencies = action::visitDependencies;
        for (Node node : dependencyResolver.resolveDependenciesFor(null, dependencies)) {
            addDependencySuccessor(node);
            processHardSuccessor.execute(node);
        }
    }

    @Override
    public Set<Node> getFinalizers() {
        return Collections.emptySet();
    }

    @Override
    public void resolveMutations() {
        // Assume has no outputs that can be destroyed or that overlap with another node
    }

    public WorkNodeAction getAction() {
        return action;
    }

    @Override
    public boolean isPublicNode() {
        return false;
    }

    @Override
    public boolean requiresMonitoring() {
        return false;
    }

    @Override
    public String toString() {
        return "work action " + action;
    }

    @Override
    public int compareTo(Node o) {
        return -1;
    }

    @Nullable
    @Override
    public ResourceLock getProjectToLock() {
        if (projectToLock != null) {
            return projectToLock.getOwner().getAccessLock();
        }
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal getOwningProject() {
        return owningProject;
    }

    @Override
    public List<ResourceLock> getResourcesToLock() {
        return Collections.emptyList();
    }

    @Override
    public void execute(NodeExecutionContext context) {
        action.run(context);
    }
}
