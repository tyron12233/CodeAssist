package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.build.ExecutionResult;


public class BuildOperationFiringBuildTreeWorkExecutor implements BuildTreeWorkExecutor {
    private final BuildTreeWorkExecutor delegate;
    private final BuildOperationExecutor executor;

    public BuildOperationFiringBuildTreeWorkExecutor(BuildTreeWorkExecutor delegate, BuildOperationExecutor executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public ExecutionResult<Void> execute(BuildTreeWorkGraph graph) {
        return executor.call(new CallableBuildOperation<ExecutionResult<Void>>() {
            @Override
            public ExecutionResult<Void> call(BuildOperationContext context) throws Exception {
                return delegate.execute(graph);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                BuildOperationDescriptor.Builder builder = BuildOperationDescriptor.displayName("Run main tasks");
                builder.metadata(BuildOperationCategory.RUN_MAIN_TASKS);
                return builder;
            }
        });
    }
}