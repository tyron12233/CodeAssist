package com.tyron.builder.api.internal.snapshot;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An immutable hierarchy of snapshots of the file system.
 *
 * Intended to store an in-memory representation of the state of the file system.
 */
public interface SnapshotHierarchy {

    /**
     * Returns the metadata stored at the absolute path if it exists.
     */
    Optional<MetadataSnapshot> findMetadata(String absolutePath);

    /**
     * Returns the snapshot stored at the absolute path if one exists.
     */
    default Optional<FileSystemLocationSnapshot> findSnapshot(String absolutePath) {
        return findMetadata(absolutePath)
                .filter(FileSystemLocationSnapshot.class::isInstance)
                .map(FileSystemLocationSnapshot.class::cast);
    }

    boolean hasDescendantsUnder(String absolutePath);

    /**
     * Returns a hierarchy augmented by the information of the snapshot at the absolute path.
     */
    SnapshotHierarchy store(String absolutePath, MetadataSnapshot snapshot, NodeDiffListener diffListener);

    /**
     * Returns a hierarchy without any information at the absolute path.
     */
    SnapshotHierarchy invalidate(String absolutePath, NodeDiffListener diffListener);

    /**
     * The empty hierarchy.
     */
    SnapshotHierarchy empty();

    /**
     * Returns all root snapshots in the hierarchy.
     */
    Stream<FileSystemLocationSnapshot> rootSnapshots();

    /**
     * Returns all root snapshots in the hierarchy below {@code absolutePath}.
     */
    Stream<FileSystemLocationSnapshot> rootSnapshotsUnder(String absolutePath);

    /**
     * Receives diff when a {@link SnapshotHierarchy} is updated.
     *
     * Only the root nodes which have been removed/added are reported.
     */
    interface NodeDiffListener {
        NodeDiffListener NOOP = new NodeDiffListener() {
            @Override
            public void nodeRemoved(FileSystemNode node) {
            }

            @Override
            public void nodeAdded(FileSystemNode node) {
            }
        };

        /**
         * Called when a node is removed during the update.
         *
         * Only called for the node which is removed, and not every node in the hierarchy which is removed.
         */
        void nodeRemoved(FileSystemNode node);

        /**
         * Called when a node is added during the update.
         *
         * Only called for the node which is added, and not every node in the hierarchy which is added.
         */
        void nodeAdded(FileSystemNode node);
    }

    /**
     * Listens to diffs to {@link FileSystemLocationSnapshot}s during an update of {@link SnapshotHierarchy}.
     *
     * Similar to {@link NodeDiffListener}, only that
     * - it listens for {@link FileSystemLocationSnapshot}s and not {@link FileSystemNode}s.
     * - it receives all the changes for one update at once.
     */
    interface SnapshotDiffListener {
        SnapshotDiffListener NOOP = (removedSnapshots, addedSnapshots) -> {};

        /**
         * Called after the update to {@link SnapshotHierarchy} finished.
         *
         * Only the roots of added/removed hierarchies are reported.
         */
        void changed(Collection<FileSystemLocationSnapshot> removedSnapshots, Collection<FileSystemLocationSnapshot> addedSnapshots);
    }
}