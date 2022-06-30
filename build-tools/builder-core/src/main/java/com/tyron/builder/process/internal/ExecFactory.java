package com.tyron.builder.process.internal;

import com.tyron.builder.api.internal.ProcessOperations;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.jvm.JavaModuleDetector;
import com.tyron.builder.internal.reflect.Instantiator;

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
