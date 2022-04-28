package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public class SubtractingFileCollection extends AbstractOpaqueFileCollection {
    private final AbstractFileCollection left;
    private final FileCollection right;

    public SubtractingFileCollection(AbstractFileCollection left, FileCollection right) {
        super(left.patternSetFactory);
        this.left = left;
        this.right = right;
    }

    public AbstractFileCollection getLeft() {
        return left;
    }

    public FileCollection getRight() {
        return right;
    }

    @Override
    public String getDisplayName() {
        return "file collection";
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        left.visitDependencies(context);
    }

    @Override
    protected Set<File> getIntrinsicFiles() {
        Set<File> files = new LinkedHashSet<File>(left.getFiles());
        files.removeAll(right.getFiles());
        return files;
    }

    @Override
    public boolean contains(File file) {
        return left.contains(file) && !right.contains(file);
    }
}