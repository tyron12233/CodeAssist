package org.gradle.workers.internal;

import org.gradle.internal.operations.BuildOperationRef;

public interface BuildOperationAwareWorker {
    DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec);

    DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec, final BuildOperationRef parentBuildOperation);
}
