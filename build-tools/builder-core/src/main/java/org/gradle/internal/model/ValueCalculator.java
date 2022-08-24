package org.gradle.internal.model;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.WorkNodeAction;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.internal.tasks.WorkNodeAction;

import javax.annotation.Nullable;

/**
 * Produces a value, potentially based on values produced by tasks or other work nodes.
 */
public interface ValueCalculator<T> extends TaskDependencyContainer {
    /**
     * See {@link WorkNodeAction#usesMutableProjectState()}.
     */
    default boolean usesMutableProjectState() {
        return false;
    }

    /**
     * See {@link WorkNodeAction#getOwningProject()}.
     */
    @Nullable
    default ProjectInternal getOwningProject() {
        return null;
    }

    @Nullable
    default WorkNodeAction getPreExecutionAction() {
        return null;
    }

    @Override
    default void visitDependencies(TaskDependencyResolveContext context) {
    }

    T calculateValue(NodeExecutionContext context);
}