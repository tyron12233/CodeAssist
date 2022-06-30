package com.tyron.builder.configuration;

import com.tyron.builder.execution.ProjectConfigurer;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.initialization.ModelConfigurationListener;
import com.tyron.builder.initialization.ProjectsEvaluatedNotifier;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.buildtree.BuildModelParameters;

public class DefaultProjectsPreparer implements ProjectsPreparer {
    private final BuildOperationExecutor buildOperationExecutor;
    private final ProjectConfigurer projectConfigurer;
    private final BuildModelParameters buildModelParameters;
    private final ModelConfigurationListener modelConfigurationListener;
    private final BuildStateRegistry buildStateRegistry;

    public DefaultProjectsPreparer(
            ProjectConfigurer projectConfigurer,
            BuildModelParameters buildModelParameters,
            ModelConfigurationListener modelConfigurationListener,
            BuildOperationExecutor buildOperationExecutor,
            BuildStateRegistry buildStateRegistry
    ) {
        this.projectConfigurer = projectConfigurer;
        this.buildModelParameters = buildModelParameters;
        this.modelConfigurationListener = modelConfigurationListener;
        this.buildOperationExecutor = buildOperationExecutor;
        this.buildStateRegistry = buildStateRegistry;
    }

    @Override
    public void prepareProjects(GradleInternal gradle) {
        if (!buildModelParameters.isConfigureOnDemand() || !gradle.isRootBuild()) {
            projectConfigurer.configureHierarchy(gradle.getRootProject());
            new ProjectsEvaluatedNotifier(buildOperationExecutor).notify(gradle);
        }

        if (gradle.isRootBuild()) {
            // Make root build substitutions available
            buildStateRegistry.afterConfigureRootBuild();
        }

        modelConfigurationListener.onConfigure(gradle);
    }
}