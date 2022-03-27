package com.tyron.builder.api.internal.snapshot;

/**
 * A snapshot of a part of the file system.
 */
public interface FileSystemSnapshot {
    /**
     * An empty snapshot.
     */
    FileSystemSnapshot EMPTY = new FileSystemSnapshot() {
        @Override
        public SnapshotVisitResult accept(FileSystemSnapshotHierarchyVisitor visitor) {
            return SnapshotVisitResult.CONTINUE;
        }

        @Override
        public SnapshotVisitResult accept(RelativePathTracker pathTracker, RelativePathTrackingFileSystemSnapshotHierarchyVisitor visitor) {
            return SnapshotVisitResult.CONTINUE;
        }
    };

    /**
     * Walks the whole hierarchy represented by this snapshot.
     *
     * The walk is depth first.
     */
    SnapshotVisitResult accept(FileSystemSnapshotHierarchyVisitor visitor);

    /**
     * Walks the whole hierarchy represented by this snapshot.
     *
     * The walk is depth first.
     */
    SnapshotVisitResult accept(RelativePathTracker pathTracker, RelativePathTrackingFileSystemSnapshotHierarchyVisitor visitor);
}