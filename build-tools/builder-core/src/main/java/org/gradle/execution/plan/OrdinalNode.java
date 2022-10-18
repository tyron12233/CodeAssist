package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.NodeExecutionContext;

import javax.annotation.Nullable;

/**
 * Represents a node in the graph that controls ordinality of destroyers and producers as they are
 * added to the task graph.  For example "clean build" on the command line implies that the user wants
 * to run the clean tasks of each project before the build tasks of each project.  Ordinal nodes ensure
 * this order by tracking the dependencies of destroyers and producers in each group of tasks added to
 * the task graph and prevents producers of a higher ordinality to run before the destroyers of a lower
 * ordinality even if the destroyers are delayed waiting on dependencies (and vice versa).
 */
public class OrdinalNode extends Node implements SelfExecutingNode {
    public enum Type {DESTROYER, PRODUCER}

    private final Type type;
    private final OrdinalGroup ordinal;

    public OrdinalNode(Type type, OrdinalGroup ordinal) {
        this.type = type;
        this.ordinal = ordinal;
        setGroup(ordinal);
    }

    @Nullable
    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver) {
    }

    @Override
    // TODO is there a better term to use here than "task group"
    public String toString() {
        return type.name().toLowerCase() + " locations for " + getGroup();
    }

    @Override
    public void execute(NodeExecutionContext context) {
    }

    public Type getType() {
        return type;
    }

    public OrdinalGroup getOrdinalGroup() {
        return ordinal;
    }

    public void addDependenciesFrom(LocalTaskNode taskNode) {
        // Only add hard successors that will actually be executed
        Node prepareNode = taskNode.getPrepareNode();
        if (taskNode.isRequired()) {
            prepareNode.require();
            addDependencySuccessor(prepareNode);
        }
    }
}