package org.gradle.internal.buildtree;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.tooling.provider.model.internal.ToolingModelScope;

import java.util.Collection;

public interface BuildTreeModelController {
    /**
     * Returns the mutable model, configuring if necessary.
     */
    GradleInternal getConfiguredModel();

    ToolingModelScope locateBuilderForDefaultTarget(String modelName, boolean param);

    ToolingModelScope locateBuilderForTarget(BuildState target, String modelName, boolean param);

    ToolingModelScope locateBuilderForTarget(ProjectState target, String modelName, boolean param);

    boolean queryModelActionsRunInParallel();

    /**
     * Runs the given actions, possibly in parallel.
     */
    void runQueryModelActions(Collection<? extends RunnableBuildOperation> actions);
}