package org.gradle.api.internal.file;

import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Factory;

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