package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.operations.BuildOperationRef;

public interface BuildOperationAwareWorker {
    DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec);

    DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec, final BuildOperationRef parentBuildOperation);
}
