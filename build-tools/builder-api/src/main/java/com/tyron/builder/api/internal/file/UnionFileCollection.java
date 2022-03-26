package com.tyron.builder.api.internal.file;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.logging.TreeFormatter;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An immutable sequence of file collections.
 */
public class UnionFileCollection extends CompositeFileCollection {
    private final ImmutableSet<FileCollectionInternal> source;

    public UnionFileCollection(FileCollectionInternal... source) {
        this.source = ImmutableSet.copyOf(source);
    }

    public UnionFileCollection(Iterable<? extends FileCollectionInternal> source) {
        this.source = ImmutableSet.copyOf(source);
    }

    @Override
    public String getDisplayName() {
        return "file collection";
    }

    @Override
    protected void appendContents(TreeFormatter formatter) {
        formatter.node("source");
        formatter.startChildren();
        for (FileCollectionInternal files : source) {
            files.describeContents(formatter);
        }
        formatter.endChildren();
    }

    public Set<? extends FileCollection> getSources() {
        return source;
    }

    @Override
    public FileCollectionInternal replace(FileCollectionInternal original, Supplier<FileCollectionInternal> supplier) {
        ImmutableSet.Builder<FileCollectionInternal> newSource = ImmutableSet.builderWithExpectedSize(source.size());
        boolean hasChanges = false;
        for (FileCollectionInternal candidate : source) {
            FileCollectionInternal newCollection = candidate.replace(original, supplier);
            hasChanges |= newCollection != candidate;
            newSource.add(newCollection);
        }
        if (hasChanges) {
            return new UnionFileCollection(newSource.build());
        } else {
            return this;
        }
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        for (FileCollectionInternal fileCollection : source) {
            visitor.accept(fileCollection);
        }
    }
}