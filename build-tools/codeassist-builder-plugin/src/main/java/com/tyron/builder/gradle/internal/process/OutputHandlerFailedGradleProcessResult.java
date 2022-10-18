package com.tyron.builder.gradle.internal.process;

import com.android.annotations.NonNull;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessResult;

public class OutputHandlerFailedGradleProcessResult implements ProcessResult {
    @NonNull
    private final ProcessException failure;

    OutputHandlerFailedGradleProcessResult(@NonNull ProcessException failure) {
        this.failure = failure;
    }

    @NonNull
    @Override
    public ProcessResult assertNormalExitValue() throws ProcessException {
        throw failure;
    }

    @Override
    public int getExitValue() {
        return -1;
    }

    @NonNull
    @Override
    public ProcessResult rethrowFailure() throws ProcessException {
        throw failure;
    }
}
