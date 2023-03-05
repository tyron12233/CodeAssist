package org.gradle.process.internal;

import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.jvm.JavaModuleDetector;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaForkOptions;

import java.util.concurrent.Executor;

/**
 * Use {@link ExecActionFactory} (for core code) or {@link org.gradle.process.ExecOperations} (for plugin code) instead.
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
