package org.gradle.internal.execution.steps;

import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;

import java.util.function.Function;

public abstract class BuildOperationStep<C extends Context, R extends Result> implements Step<C, R> {
    private final BuildOperationExecutor buildOperationExecutor;

    protected BuildOperationStep(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    protected <T> T operation(Function<BuildOperationContext, T> operation, BuildOperationDescriptor.Builder description) {
        return buildOperationExecutor.call(new CallableBuildOperation<T>() {
            @Override
            public T call(BuildOperationContext context) {
                return operation.apply(context);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return description;
            }
        });
    }
}
