package com.tyron.builder.api.internal.file;


import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.util.Iterator;
import java.util.Set;

/**
 * A base class for {@link FileCollection} implementations that are not composed from other file collections.
 */
public abstract class AbstractOpaqueFileCollection extends AbstractFileCollection {
    public AbstractOpaqueFileCollection() {
    }

    public AbstractOpaqueFileCollection(Factory<PatternSet> patternSetFactory) {
        super(patternSetFactory);
    }

    /**
     * This is final - override {@link #getIntrinsicFiles()} instead.
     */
    @Override
    public final Set<File> getFiles() {
        return getIntrinsicFiles();
    }

    /**
     * This is final - override {@link #getIntrinsicFiles()} instead.
     */
    @Override
    public final Iterator<File> iterator() {
        return getIntrinsicFiles().iterator();
    }

    @Override
    protected void visitContents(FileCollectionStructureVisitor visitor) {
        visitor.visitCollection(OTHER, this);
    }

    abstract protected Set<File> getIntrinsicFiles();
}