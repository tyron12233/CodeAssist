package org.gradle.api.internal.file.collections;

import org.gradle.api.file.FileCollection;
import org.gradle.internal.Factory;
import org.gradle.api.internal.file.AbstractOpaqueFileCollection;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.Buildable;
import org.gradle.api.tasks.util.PatternSet;

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