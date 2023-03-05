package org.gradle.configuration;

import org.gradle.execution.ProjectConfigurer;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.initialization.ModelConfigurationListener;
import org.gradle.initialization.ProjectsEvaluatedNotifier;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.buildtree.BuildModelParameters;

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