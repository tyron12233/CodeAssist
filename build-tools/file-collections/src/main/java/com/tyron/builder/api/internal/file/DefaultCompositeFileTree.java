package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public class DefaultCompositeFileTree extends CompositeFileTree {
    private final Collection<? extends FileTreeInternal> fileTrees;

    public DefaultCompositeFileTree(Factory<PatternSet> patternSetFactory, List<?
            extends FileTreeInternal> fileTrees) {
        super(patternSetFactory);
        this.fileTrees = fileTrees;
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        for (FileTreeInternal fileTree : fileTrees) {
            visitor.accept(fileTree);
        }
    }

    @Override
    public String getDisplayName() {
        return "file tree";
    }
}