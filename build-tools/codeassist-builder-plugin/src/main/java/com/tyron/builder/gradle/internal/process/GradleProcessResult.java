package com.tyron.builder.gradle.internal.process;

import com.android.annotations.NonNull;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessInfo;
import com.android.ide.common.process.ProcessResult;
import com.google.common.base.Joiner;
import org.gradle.process.ExecResult;
import org.gradle.process.internal.ExecException;

/**
 */
class GradleProcessResult implements ProcessResult {

    @NonNull
    private final ExecResult result;

    @NonNull
    private final ProcessInfo processInfo;

    GradleProcessResult(@NonNull ExecResult result, @NonNull ProcessInfo processInfo) {
        this.result = result;
        this.processInfo = processInfo;
    }

    @NonNull
    @Override
    public ProcessResult assertNormalExitValue() throws ProcessException {
        try {
            result.assertNormalExitValue();
        } catch (ExecException e) {
            throw buildProcessException(e);
        }

        return this;
    }

    @Override
    public int getExitValue() {
        return result.getExitValue();
    }

    @NonNull
    @Override
    public ProcessResult rethrowFailure() throws ProcessException {
        try {
            result.rethrowFailure();
        } catch (ExecException e) {
            throw buildProcessException(e);
        }
        return this;
    }

    @NonNull
    private ProcessException buildProcessException(@NonNull ExecException e) {
        return new ProcessException(
                String.format(
                        "Error while executing %s with arguments {%s}",
                        processInfo.getDescription(),
                        Joiner.on(' ').join(processInfo.getArgs())),
                e);
    }
}