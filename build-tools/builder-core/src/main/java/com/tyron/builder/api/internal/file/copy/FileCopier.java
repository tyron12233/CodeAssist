package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

public class FileCopier {
    private final Deleter deleter;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileResolver fileResolver;
    private final Factory<PatternSet> patternSetFactory;
    private final ObjectFactory objectFactory;
    private final FileSystem fileSystem;
    private final Instantiator instantiator;
    private final DocumentationRegistry documentationRegistry;

    public FileCopier(
            Deleter deleter,
            DirectoryFileTreeFactory directoryFileTreeFactory,
            FileCollectionFactory fileCollectionFactory,
            FileResolver fileResolver,
            Factory<PatternSet> patternSetFactory,
            ObjectFactory objectFactory,
            FileSystem fileSystem,
            Instantiator instantiator,
            DocumentationRegistry documentationRegistry
    ) {
        this.deleter = deleter;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileResolver = fileResolver;
        this.patternSetFactory = patternSetFactory;
        this.objectFactory = objectFactory;
        this.fileSystem = fileSystem;
        this.instantiator = instantiator;
        this.documentationRegistry = documentationRegistry;
    }

    private DestinationRootCopySpec createCopySpec(Action<? super CopySpec> action) {
        DefaultCopySpec copySpec = new DefaultCopySpec(fileCollectionFactory, instantiator, patternSetFactory);
        DestinationRootCopySpec destinationRootCopySpec = new DestinationRootCopySpec(fileResolver, copySpec);
        CopySpec wrapped = instantiator.newInstance(CopySpecWrapper.class, destinationRootCopySpec);
        action.execute(wrapped);
        return destinationRootCopySpec;
    }

    public WorkResult copy(Action<? super CopySpec> action) {
        DestinationRootCopySpec copySpec = createCopySpec(action);
        File destinationDir = copySpec.getDestinationDir();
        return doCopy(copySpec, getCopyVisitor(destinationDir));
    }

    public WorkResult sync(Action<? super CopySpec> action) {
        DestinationRootCopySpec copySpec = createCopySpec(action);
        File destinationDir = copySpec.getDestinationDir();
        return doCopy(copySpec, new SyncCopyActionDecorator(destinationDir, getCopyVisitor(destinationDir), deleter, directoryFileTreeFactory));
    }

    private FileCopyAction getCopyVisitor(File destination) {
        return new FileCopyAction(fileResolver.newResolver(destination));
    }

    private WorkResult doCopy(CopySpecInternal copySpec, CopyAction visitor) {
        CopyActionExecuter visitorDriver = new CopyActionExecuter(instantiator, objectFactory, fileSystem, false, documentationRegistry);
        return visitorDriver.execute(copySpec, visitor);
    }

}