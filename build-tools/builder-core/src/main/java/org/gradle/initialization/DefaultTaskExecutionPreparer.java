package org.gradle.initialization;

import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.execution.BuildConfigurationActionExecuter;
import org.gradle.internal.buildtree.BuildModelParameters;

public class DefaultTaskExecutionPreparer implements TaskExecutionPreparer {
    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildConfigurationActionExecuter buildConfigurationActionExecuter;
    private final BuildModelParameters buildModelParameters;

    public DefaultTaskExecutionPreparer(
            BuildConfigurationActionExecuter buildConfigurationActionExecuter,
            BuildOperationExecutor buildOperationExecutor,
            BuildModelParameters buildModelParameters
    ) {
        this.buildConfigurationActionExecuter = buildConfigurationActionExecuter;
        this.buildOperationExecutor = buildOperationExecutor;
        this.buildModelParameters = buildModelParameters;
    }

    @Override
    public void prepareForTaskExecution(GradleInternal gradle, ExecutionPlan plan) {
        buildConfigurationActionExecuter.select(gradle, plan);

        if (buildModelParameters.isConfigureOnDemand() && gradle.isRootBuild()) {
            new ProjectsEvaluatedNotifier(buildOperationExecutor).notify(gradle);
        }
    }
}