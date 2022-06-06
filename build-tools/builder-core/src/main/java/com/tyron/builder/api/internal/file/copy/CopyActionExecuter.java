package com.tyron.builder.api.internal.file.copy;


import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.tasks.WorkResult;

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