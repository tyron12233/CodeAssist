package com.tyron.builder.api.internal.buildTree;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.build.BuildState;

import java.util.Collection;

public interface BuildTreeModelController {
    /**
     * Returns the mutable model, configuring if necessary.
     */
    GradleInternal getConfiguredModel();

//    ToolingModelScope locateBuilderForDefaultTarget(String modelName, boolean param);
//
//    ToolingModelScope locateBuilderForTarget(BuildState target, String modelName, boolean param);
//
//    ToolingModelScope locateBuilderForTarget(ProjectState target, String modelName, boolean param);

    boolean queryModelActionsRunInParallel();

    /**
     * Runs the given actions, possibly in parallel.
     */
    void runQueryModelActions(Collection<? extends Runnable> actions);
}