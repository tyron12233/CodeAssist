package com.tyron.builder.execution;

import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.internal.build.ExecutionResult;

public class BuildOperationFiringBuildWorkerExecutor implements BuildWorkExecutor {
    private final BuildWorkExecutor delegate;
    private final BuildOperationExecutor buildOperationExecutor;

    public BuildOperationFiringBuildWorkerExecutor(BuildWorkExecutor delegate, BuildOperationExecutor buildOperationExecutor) {
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public ExecutionResult<Void> execute(GradleInternal gradle, ExecutionPlan plan) {
        return buildOperationExecutor.call(new ExecuteTasks(gradle, plan));
    }

    private class ExecuteTasks implements CallableBuildOperation<ExecutionResult<Void>> {
        private final GradleInternal gradle;
        private final ExecutionPlan plan;

        public ExecuteTasks(GradleInternal gradle, ExecutionPlan plan) {
            this.gradle = gradle;
            this.plan = plan;
        }

        @Override
        public ExecutionResult<Void> call(BuildOperationContext context) throws Exception {
            ExecutionResult<Void> result = delegate.execute(gradle, plan);
            if (!result.getFailures().isEmpty()) {
                context.failed(result.getFailure());
            }
            return result;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            BuildOperationDescriptor.Builder builder = BuildOperationDescriptor.displayName(gradle.contextualize("Run tasks"));
            if (gradle.isRootBuild()) {
                long buildStartTime = gradle.getServices().get(BuildRequestMetaData.class).getStartTime();
                builder.details(new RunRootBuildWorkBuildOperationType.Details(buildStartTime));
            }
            builder.metadata(BuildOperationCategory.RUN_WORK);
            builder.totalProgress(gradle.getTaskGraph().size());
            return builder;
        }
    }
}
