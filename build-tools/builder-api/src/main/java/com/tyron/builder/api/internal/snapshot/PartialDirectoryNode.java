package com.tyron.builder.api.internal.snapshot;

import java.util.Optional;

/**
 * An incomplete snapshot of an existing directory.
 *
 * May include some of its children.
 */
public class PartialDirectoryNode extends AbstractIncompleteFileSystemNode {

    public static PartialDirectoryNode withoutKnownChildren() {
        return new PartialDirectoryNode(EmptyChildMap.getInstance());
    }

    public PartialDirectoryNode(ChildMap<? extends FileSystemNode> children) {
        super(children);
    }

    @Override
    protected FileSystemNode withIncompleteChildren(ChildMap<? extends FileSystemNode> newChildren) {
        return new PartialDirectoryNode(newChildren);
    }

    @Override
    protected Optional<FileSystemNode> withAllChildrenRemoved() {
        return Optional.of(children.isEmpty() ? this : withoutKnownChildren());
    }

    @Override
    public Optional<MetadataSnapshot> getSnapshot() {
        return Optional.of(MetadataSnapshot.DIRECTORY);
    }

    @Override
    protected FileSystemNode withIncompleteChildren() {
        return this;
    }
}