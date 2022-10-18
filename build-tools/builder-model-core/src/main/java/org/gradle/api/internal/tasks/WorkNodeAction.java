package org.gradle.api.internal.tasks;

import org.gradle.api.Project;

import javax.annotation.Nullable;

import org.gradle.api.Project;

import javax.annotation.Nullable;

/**
 * A self-contained action to run as a node in the work graph. Can be included in the dependencies of a {@link TaskDependencyContainer}.
 */
public interface WorkNodeAction {
    /**
     * Does this action require exclusive access to the mutable state of its owning project?
     */
    default boolean usesMutableProjectState() {
        return false;
    }

    /**
     * Returns the project which the action belongs to. This is used to determine the services to expose to {@link #run(NodeExecutionContext)} via the context.
     * Returning non-null here does not imply any kind of exclusive access to the project, unless {@link #usesMutableProjectState()} returns true.
     */
    @Nullable
    default Project getOwningProject() {
        return null;
    }

    default void visitDependencies(TaskDependencyResolveContext context) {
    }

    /**
     * Run the action, throwing any failure.
     */
    void run(NodeExecutionContext context);

    @Nullable
    default WorkNodeAction getPreExecutionNode() {
        return null;
    }
}