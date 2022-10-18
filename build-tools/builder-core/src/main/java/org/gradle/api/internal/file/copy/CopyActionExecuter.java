package org.gradle.api.internal.file.copy;


import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.WorkResult;

public class CopyActionExecuter {

    private final Instantiator instantiator;
    private final ObjectFactory objectFactory;
    private final FileSystem fileSystem;
    private final boolean reproducibleFileOrder;
    private final DocumentationRegistry documentationRegistry;

    public CopyActionExecuter(Instantiator instantiator, ObjectFactory objectFactory, FileSystem fileSystem, boolean reproducibleFileOrder, DocumentationRegistry documentationRegistry) {
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
        this.fileSystem = fileSystem;
        this.reproducibleFileOrder = reproducibleFileOrder;
        this.documentationRegistry = documentationRegistry;
    }

    public WorkResult execute(final CopySpecInternal spec, CopyAction action) {
        final CopyAction effectiveVisitor = new DuplicateHandlingCopyActionDecorator(
                new NormalizingCopyActionDecorator(action, fileSystem),
                documentationRegistry
        );

        CopyActionProcessingStream processingStream = new CopySpecBackedCopyActionProcessingStream(spec, instantiator, objectFactory, fileSystem, reproducibleFileOrder);
        return effectiveVisitor.execute(processingStream);
    }

}