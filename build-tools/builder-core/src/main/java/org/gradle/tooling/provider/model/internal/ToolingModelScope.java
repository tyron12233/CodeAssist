package org.gradle.tooling.provider.model.internal;

import org.gradle.api.internal.project.ProjectState;
import org.gradle.tooling.provider.model.UnknownModelException;

import javax.annotation.Nullable;
import java.util.function.Function;

public interface ToolingModelScope {
    @Nullable
    ProjectState getTarget();

    /**
     * Creates the given model
     */
    @Nullable
    Object getModel(String modelName, @Nullable Function<Class<?>, Object> parameterFactory) throws UnknownModelException;
}
