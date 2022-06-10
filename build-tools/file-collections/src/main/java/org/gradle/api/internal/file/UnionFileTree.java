package org.gradle.api.internal.file;

import com.google.common.collect.Sets;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.internal.Cast;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

public class UnionFileTree extends CompositeFileTree {
    private final Set<FileTreeInternal> sourceTrees;
    private final String displayName;

    public UnionFileTree(FileTreeInternal... sourceTrees) {
        this("file tree", Arrays.asList(sourceTrees));
    }

    public UnionFileTree(String displayName, FileTreeInternal... sourceTrees) {
        this(displayName, Arrays.asList(sourceTrees));
    }

    public UnionFileTree(String displayName, Collection<? extends FileTreeInternal> sourceTrees) {
        this.displayName = displayName;
        this.sourceTrees = Sets.newLinkedHashSet(sourceTrees);
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        for (FileTreeInternal sourceTree : sourceTrees) {
            visitor.accept(sourceTree);
        }
    }

    public void addToUnion(FileCollection source) {
        if (!(source instanceof FileTree)) {
            throw new UnsupportedOperationException(String.format("Can only add FileTree instances to %s.", getDisplayName()));
        }

        sourceTrees.add(Cast.cast(FileTreeInternal.class, source));
    }
}
