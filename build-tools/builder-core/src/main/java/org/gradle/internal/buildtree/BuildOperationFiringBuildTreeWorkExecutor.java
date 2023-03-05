package org.gradle.internal.buildtree;

import org.gradle.internal.operations.BuildOperationCategory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.build.ExecutionResult;


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