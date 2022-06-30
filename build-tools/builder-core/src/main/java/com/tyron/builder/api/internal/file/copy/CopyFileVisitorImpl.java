package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.ReproducibleFileVisitor;
import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;

public class CopyFileVisitorImpl implements ReproducibleFileVisitor {
    private final CopySpecResolver copySpecResolver;
    private final CopyActionProcessingStreamAction action;
    private final Instantiator instantiator;
    private final ObjectFactory objectFactory;
    private final FileSystem fileSystem;
    private final boolean reproducibleFileOrder;

    public CopyFileVisitorImpl(CopySpecResolver spec, CopyActionProcessingStreamAction action, Instantiator instantiator, ObjectFactory objectFactory, FileSystem fileSystem,
                               boolean reproducibleFileOrder) {
        this.copySpecResolver = spec;
        this.action = action;
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
        this.fileSystem = fileSystem;
        this.reproducibleFileOrder = reproducibleFileOrder;
    }

    @Override
    public void visitDir(FileVisitDetails dirDetails) {
        processDir(dirDetails);
    }

    @Override
    public void visitFile(FileVisitDetails fileDetails) {
        processFile(fileDetails);
    }

    private void processDir(FileVisitDetails visitDetails) {
        DefaultFileCopyDetails details = createDefaultFileCopyDetails(visitDetails);
        action.processFile(details);
    }

    private void processFile(FileVisitDetails visitDetails) {
        DefaultFileCopyDetails details = createDefaultFileCopyDetails(visitDetails);
        for (Action<? super FileCopyDetails> action : copySpecResolver.getAllCopyActions()) {
            action.execute(details);
            if (details.isExcluded()) {
                return;
            }
        }
        action.processFile(details);
    }

    private DefaultFileCopyDetails createDefaultFileCopyDetails(FileVisitDetails visitDetails) {
        return instantiator.newInstance(DefaultFileCopyDetails.class, visitDetails, copySpecResolver, objectFactory, fileSystem);
    }

    @Override
    public boolean isReproducibleFileOrder() {
        return reproducibleFileOrder;
    }
}
