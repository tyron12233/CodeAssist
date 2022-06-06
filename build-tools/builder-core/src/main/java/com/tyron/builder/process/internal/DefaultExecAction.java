package com.tyron.builder.process.internal;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.process.ExecResult;

import java.util.concurrent.Executor;

/**
 * Use {@link ExecActionFactory} (for core code) or {@link com.tyron.builder.process.ExecOperations} (for plugin code) instead.
 */
public class DefaultExecAction extends DefaultExecHandleBuilder implements ExecAction {
    public DefaultExecAction(PathToFileResolver fileResolver, Executor executor, BuildCancellationToken buildCancellationToken) {
        super(fileResolver, executor, buildCancellationToken);
    }

    @Override
    public ExecResult execute() {
        ExecHandle execHandle = build();
        ExecResult execResult = execHandle.start().waitForFinish();
        if (!isIgnoreExitValue()) {
            execResult.assertNormalExitValue();
        }
        return execResult;
    }
}
