package com.tyron.builder.process.internal.worker.request;

import com.google.common.base.Preconditions;
import com.tyron.builder.internal.operations.BuildOperationRef;

public class Request {
    private final Object arg;
    private final BuildOperationRef buildOperation;

    public Request(Object arg, BuildOperationRef buildOperation) {
        Preconditions.checkNotNull(buildOperation);
        this.arg = arg;
        this.buildOperation = buildOperation;
    }

    public Object getArg() {
        return arg;
    }

    public BuildOperationRef getBuildOperation() {
        return buildOperation;
    }
}
