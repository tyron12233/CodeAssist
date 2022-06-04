package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;

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
