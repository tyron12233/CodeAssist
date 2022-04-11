package com.tyron.builder.api.internal.snapshot;

import java.util.Optional;

/**
 * An incomplete snapshot where we don’t know if it’s a file, a directory, or nothing.
 *
 * The snapshot must have children.
 * It is created when we store missing files underneath it, so that we don’t have to query them again and again.
 */
public class UnknownFileSystemNode extends AbstractIncompleteFileSystemNode {

    public UnknownFileSystemNode(ChildMap<? extends FileSystemNode> children) {
        super(children);
        assert !children.isEmpty();
    }

    @Override
    public Optional<MetadataSnapshot> getSnapshot() {
        return Optional.empty();
    }

    @Override
    protected FileSystemNode withIncompleteChildren(ChildMap<? extends FileSystemNode> merged) {
        return new UnknownFileSystemNode(merged);
    }

    @Override
    protected Optional<FileSystemNode> withAllChildrenRemoved() {
        return Optional.empty();
    }

    @Override
    protected FileSystemNode withIncompleteChildren() {
        return this;
    }
}