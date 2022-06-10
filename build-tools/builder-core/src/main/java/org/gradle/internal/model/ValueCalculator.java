package org.gradle.internal.model;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.WorkNodeAction;

/**
 * Produces a value, potentially based on values produced by tasks or other work nodes.
 */
public interface ValueCalculator<T> extends TaskDependencyContainer {
    /**
     * See {@link WorkNodeAction#usesMutableProjectState()}.
     */
    boolean usesMutableProjectState();

    /**
     * See {@link WorkNodeAction#getOwningProject()}.
     */
    ProjectInternal getOwningProject();

    T calculateValue(NodeExecutionContext context);
}
