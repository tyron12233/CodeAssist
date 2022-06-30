package com.tyron.builder.initialization;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.execution.BuildConfigurationActionExecuter;
import com.tyron.builder.internal.buildtree.BuildModelParameters;

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