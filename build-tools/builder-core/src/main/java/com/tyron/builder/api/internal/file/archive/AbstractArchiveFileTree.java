package com.tyron.builder.api.internal.file.archive;

import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.provider.Provider;

import javax.annotation.Nullable;
import java.io.File;

public abstract class AbstractArchiveFileTree implements FileSystemMirroringFileTree, TaskDependencyContainer {
    abstract protected Provider<File> getBackingFileProvider();

    @Nullable
    private File getBackingFile() {
        return getBackingFileProvider().getOrNull();
    }

    @Override
    public void visitStructure(FileCollectionStructureVisitor visitor, FileTreeInternal owner) {
        File backingFile = getBackingFile();
        if (backingFile != null) {
            visitor.visitFileTreeBackedByFile(backingFile, owner, this);
        } else {
            visitor.visitGenericFileTree(owner, this);
        }
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(getBackingFileProvider());
    }
}
