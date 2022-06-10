package org.gradle.api.internal.file.copy;

import org.gradle.api.Action;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.reflect.Instantiator;

public class CopySpecActionImpl implements Action<CopySpecResolver> {
    private final CopyActionProcessingStreamAction action;
    private final Instantiator instantiator;
    private final ObjectFactory objectFactory;
    private final FileSystem fileSystem;
    private final boolean reproducibleFileOrder;

    public CopySpecActionImpl(CopyActionProcessingStreamAction action, Instantiator instantiator, ObjectFactory objectFactory, FileSystem fileSystem, boolean reproducibleFileOrder) {
        this.action = action;
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
        this.fileSystem = fileSystem;
        this.reproducibleFileOrder = reproducibleFileOrder;
    }

    @Override
    public void execute(final CopySpecResolver specResolver) {
        FileTree source = specResolver.getSource();
        source.visit(new CopyFileVisitorImpl(specResolver, action, instantiator, objectFactory, fileSystem, reproducibleFileOrder));
    }
}
