package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public class OrdinalNode extends Node implements SelfExecutingNode {
    public enum Type { DESTROYER, PRODUCER }

    private final Type type;
    private final int ordinal;

    public OrdinalNode(Type type, int ordinal) {
        this.type = type;
        this.ordinal = ordinal;
    }

    @Nullable
    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void rethrowNodeFailure() { }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) { }

    @javax.annotation.Nullable
    @Override
    public ResourceLock getProjectToLock() {
        return null;
    }

    @javax.annotation.Nullable
    @Override
    public ProjectInternal getOwningProject() {
        return null;
    }

    @Override
    public List<? extends ResourceLock> getResourcesToLock() {
        return null;
    }

    @Override
    // TODO is there a better term to use here than "task group"
    public String toString() {
        return type.name().toLowerCase() + " locations for task group " + ordinal;
    }

    @Override
    public int compareTo(Node o) {
        return -1;
    }

    @Override
    public void execute(NodeExecutionContext context) { }

    public Type getType() {
        return type;
    }

    public int getOrdinal() {
        return ordinal;
    }
}
