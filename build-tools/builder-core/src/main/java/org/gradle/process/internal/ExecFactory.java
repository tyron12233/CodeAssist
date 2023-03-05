package org.gradle.process.internal;

import org.gradle.api.internal.ProcessOperations;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.model.ObjectFactory;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.jvm.JavaModuleDetector;
import org.gradle.internal.reflect.Instantiator;

/**
 * Manages forking/spawning processes.
 */
public interface ExecFactory extends ExecActionFactory, ExecHandleFactory, JavaExecHandleFactory, JavaForkOptionsFactory, ProcessOperations {
    /**
     * Creates a new factory for the given context.
     */
    ExecFactory forContext(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, ObjectFactory objectFactory);

    /**
     * Creates a new factory for the given context.
     */
    ExecFactory forContext(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, ObjectFactory objectFactory, JavaModuleDetector javaModuleDetector);

    /**
     * Creates a new factory for the given context.
     */
    ExecFactory forContext(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, BuildCancellationToken buildCancellationToken, ObjectFactory objectFactory, JavaModuleDetector javaModuleDetector);
}
