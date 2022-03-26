package com.tyron.builder.api.internal.snapshot;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Any snapshot in the tree of the virtual file system.
 */
public interface FileSystemNode {
    /**
     * The snapshot information at this node.
     *
     * {@link Optional#empty()} if no information is available.
     */
    Optional<MetadataSnapshot> getSnapshot();

    boolean hasDescendants();

    /**
     * Returns all the snapshot roots accessible from the node.
     */
    Stream<FileSystemLocationSnapshot> rootSnapshots();

    /*
     * Gets a snapshot from the current node with relative path filePath.substring(offset).
     *
     * When calling this method, the caller needs to make sure the snapshot is a child of this node.
     */
    Optional<MetadataSnapshot> getSnapshot(VfsRelativePath relativePath, CaseSensitivity caseSensitivity);

    Optional<FileSystemNode> getNode(VfsRelativePath relativePath, CaseSensitivity caseSensitivity);

    /**
     * Stores information to the virtual file system that we have learned about.
     *
     * Complete information, like {@link FileSystemLocationSnapshot}s, are not touched nor replaced.
     */
    FileSystemNode store(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, MetadataSnapshot snapshot, SnapshotHierarchy.NodeDiffListener diffListener);

    /**
     * Invalidates part of the node.
     */
    Optional<FileSystemNode> invalidate(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, SnapshotHierarchy.NodeDiffListener diffListener);
}