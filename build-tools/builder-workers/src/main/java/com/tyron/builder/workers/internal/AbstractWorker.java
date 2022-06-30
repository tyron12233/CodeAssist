package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.CallableBuildOperation;

public abstract class AbstractWorker implements BuildOperationAwareWorker {

    public static final Result RESULT = new Result();

    private final BuildOperationExecutor buildOperationExecutor;

    AbstractWorker(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec) {
        return execute(spec, buildOperationExecutor.getCurrentOperation());
    }

    DefaultWorkResult executeWrappedInBuildOperation(final IsolatedParametersActionExecutionSpec<?> spec, final BuildOperationRef parentBuildOperation, final Work work) {
        return buildOperationExecutor.call(new CallableBuildOperation<DefaultWorkResult>() {
            @Override
            public DefaultWorkResult call(BuildOperationContext context) {
                DefaultWorkResult result = work.execute(spec);
                context.setResult(RESULT);
                context.failed(result.getException());
                return result;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName(spec.getDisplayName())
                    .parent(parentBuildOperation)
                    .details(new Details(spec.getActionImplementationClassName(), spec.getDisplayName()));
            }
        });
    }

    interface Work {
        DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec);
    }

    static class Details implements ExecuteWorkItemBuildOperationType.Details {

        private final String className;
        private final String displayName;

        public Details(String className, String displayName) {
            this.className = className;
            this.displayName = displayName;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

    }

    static class Result implements ExecuteWorkItemBuildOperationType.Result {
    }

}
