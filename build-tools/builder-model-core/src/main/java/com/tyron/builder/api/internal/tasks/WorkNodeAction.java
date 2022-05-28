package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.BuildProject;

import javax.annotation.Nullable;

/**
 * A self-contained action to run as a node in the work graph. Can be included in the dependencies of a {@link TaskDependencyContainer}.
 */
public interface WorkNodeAction {
    /**
     * Does this action require exclusive access to the mutable state of its owning project?
     */
    boolean usesMutableProjectState();

    /**
     * Returns the project which the action belongs to. This is used to determine the services to expose to {@link #run(NodeExecutionContext)} via the context.
     * Returning non-null here does not imply any kind of exclusive access to the project, unless {@link #usesMutableProjectState()} returns true.
     */
    @Nullable
    BuildProject getOwningProject();

    void visitDependencies(TaskDependencyResolveContext context);

    /**
     * Run the action, throwing any failure.
     */
    void run(NodeExecutionContext context);
}
