package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;

public class CopySpecBackedCopyActionProcessingStream implements CopyActionProcessingStream {

    private final CopySpecInternal spec;
    private final Instantiator instantiator;
    private final ObjectFactory objectFactory;
    private final FileSystem fileSystem;
    private final boolean reproducibleFileOrder;

    public CopySpecBackedCopyActionProcessingStream(CopySpecInternal spec, Instantiator instantiator, ObjectFactory objectFactory, FileSystem fileSystem, boolean reproducibleFileOrder) {
        this.spec = spec;
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
        this.fileSystem = fileSystem;
        this.reproducibleFileOrder = reproducibleFileOrder;
    }

    @Override
    public void process(final CopyActionProcessingStreamAction action) {
        spec.walk(new CopySpecActionImpl(action, instantiator, objectFactory, fileSystem, reproducibleFileOrder));
    }
}
