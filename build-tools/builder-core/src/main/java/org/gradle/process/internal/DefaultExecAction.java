package org.gradle.process.internal;

import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.process.ExecResult;

import java.util.concurrent.Executor;

/**
 * Use {@link ExecActionFactory} (for core code) or {@link org.gradle.process.ExecOperations} (for plugin code) instead.
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
