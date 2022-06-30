package com.tyron.builder.process.internal;

import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.jvm.JavaModuleDetector;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.JavaForkOptions;

import java.util.concurrent.Executor;

/**
 * Use {@link ExecActionFactory} (for core code) or {@link com.tyron.builder.process.ExecOperations} (for plugin code) instead.
 */
public class DefaultJavaExecAction extends JavaExecHandleBuilder implements JavaExecAction {
    public DefaultJavaExecAction(
        FileResolver fileResolver,
        FileCollectionFactory fileCollectionFactory,
        ObjectFactory objectFactory,
        Executor executor,
        BuildCancellationToken buildCancellationToken,
        TemporaryFileProvider temporaryFileProvider,
        JavaModuleDetector javaModuleDetector,
        JavaForkOptions javaOptions
    ) {
        super(fileResolver, fileCollectionFactory, objectFactory, executor, buildCancellationToken, temporaryFileProvider, javaModuleDetector, javaOptions);
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
