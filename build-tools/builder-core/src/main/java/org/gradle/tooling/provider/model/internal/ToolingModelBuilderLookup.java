package org.gradle.tooling.provider.model.internal;

import org.gradle.api.internal.project.ProjectState;
import org.gradle.configuration.internal.UserCodeApplicationContext;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.UnknownModelException;

import javax.annotation.Nullable;

@ServiceScope(Scopes.Build.class)
public interface ToolingModelBuilderLookup {
    @Nullable
    Registration find(String modelName);

    /**
     * Locates a builder for a project-scoped model.
     */
    Builder locateForClientOperation(String modelName, boolean parameter, ProjectState target) throws UnknownModelException;

    /**
     * Locates a builder for a build-scoped model.
     */
    @Nullable
    Builder maybeLocateForBuildScope(String modelName, boolean parameter, BuildState target);

    interface Registration {
        ToolingModelBuilder getBuilder();

        @Nullable
        UserCodeApplicationContext.Application getRegisteredBy();
    }

    interface Builder {
        @Nullable
        Class<?> getParameterType();

        Object build(@Nullable Object parameter);
    }
}
