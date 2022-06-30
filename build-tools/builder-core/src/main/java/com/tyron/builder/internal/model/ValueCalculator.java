package com.tyron.builder.internal.model;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.WorkNodeAction;

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
