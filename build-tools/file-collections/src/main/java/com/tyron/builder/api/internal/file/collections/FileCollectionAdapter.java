package com.tyron.builder.api.internal.file.collections;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.AbstractOpaqueFileCollection;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.Buildable;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.Set;

/**
 * Adapts a {@link MinimalFileSet} into a full {@link FileCollection}.
 */
public class FileCollectionAdapter extends AbstractOpaqueFileCollection {
    private final MinimalFileSet fileSet;

    public FileCollectionAdapter(MinimalFileSet fileSet) {
        this.fileSet = fileSet;
    }

    public FileCollectionAdapter(MinimalFileSet fileSet, Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
        this.fileSet = fileSet;
    }

    @Override
    public String getDisplayName() {
        return fileSet.getDisplayName();
    }

    @Override
    protected Set<File> getIntrinsicFiles() {
        return fileSet.getFiles();
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        if (fileSet instanceof Buildable) {
            context.add(fileSet);
        }
    }
}