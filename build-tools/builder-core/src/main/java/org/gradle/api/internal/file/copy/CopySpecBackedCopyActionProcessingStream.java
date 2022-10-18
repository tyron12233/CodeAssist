package org.gradle.api.internal.file.copy;

import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.reflect.Instantiator;

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
