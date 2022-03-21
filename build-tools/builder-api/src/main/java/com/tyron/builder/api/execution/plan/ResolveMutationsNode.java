package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class ResolveMutationsNode extends Node implements SelfExecutingNode {
    private final LocalTaskNode node;
    private final NodeValidator nodeValidator;
    private Exception failure;

    public ResolveMutationsNode(LocalTaskNode node, NodeValidator nodeValidator) {
        this.node = node;
        this.nodeValidator = nodeValidator;
    }

    public Node getNode() {
        return node;
    }

    @Override
    public String toString() {
        return "Resolve mutations for " + node;
    }

    @Override
    public int compareTo(Node o) {
        return -1;
    }

    @Nullable
    @Override
    public Throwable getNodeFailure() {
        return failure;
    }

    @Override
    public void rethrowNodeFailure() {
        throw new RuntimeException(failure);
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
    }

    @javax.annotation.Nullable
    @Override
    public ResourceLock getProjectToLock() {
        return node.getProjectToLock();
    }

    @javax.annotation.Nullable
    @Override
    public ProjectInternal getOwningProject() {
        return node.getOwningProject();
    }

    @Override
    public List<? extends ResourceLock> getResourcesToLock() {
        return node.getResourcesToLock();
    }

//    @Nullable
//    @Override
//    public ResourceLock getProjectToLock() {
//        return node.getProjectToLock();
//    }
//
//    @Nullable
//    @Override
//    public ProjectInternal getOwningProject() {
//        return node.getOwningProject();
//    }
//
//    @Override
//    public List<? extends ResourceLock> getResourcesToLock() {
//        return Collections.emptyList();
//    }

    @Override
    public void execute(NodeExecutionContext context) {
        try {
            MutationInfo mutations = node.getMutationInfo();
            node.resolveMutations();
            mutations.hasValidationProblem = nodeValidator.hasValidationProblems(node);
        } catch (Exception e) {
            failure = e;
        }
    }
}