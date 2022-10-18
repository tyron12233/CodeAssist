package org.gradle.api.internal.file.copy;

import org.gradle.api.Action;
import org.gradle.api.file.CopySpec;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.Factory;
import org.gradle.internal.file.Deleter;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.util.PatternSet;

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