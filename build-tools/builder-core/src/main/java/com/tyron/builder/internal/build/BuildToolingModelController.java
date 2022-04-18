package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.GradleInternal;

/**
 * Coordinates the building of tooling models.
 */
public interface BuildToolingModelController {
    /**
     * Returns the mutable model, configuring if necessary.
     */
    GradleInternal getConfiguredModel();

//    ToolingModelScope locateBuilderForTarget(String modelName, boolean param);
//
//    ToolingModelScope locateBuilderForTarget(ProjectState target, String modelName, boolean param);
}