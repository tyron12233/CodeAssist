package com.tyron.builder.api.internal.snapshot;

/**
 * The snapshot of a leaf element in the file system that can have no children of its own.
 */
public interface FileSystemLeafSnapshot extends FileSystemLocationSnapshot {
    @Override
    default SnapshotVisitResult accept(FileSystemSnapshotHierarchyVisitor visitor) {
        return visitor.visitEntry(this);
    }

    @Override
    default SnapshotVisitResult accept(RelativePathTracker pathTracker, RelativePathTrackingFileSystemSnapshotHierarchyVisitor visitor) {
        pathTracker.enter(getName());
        try {
            return visitor.visitEntry(this, pathTracker);
        } finally {
            pathTracker.leave();
        }
    }
}