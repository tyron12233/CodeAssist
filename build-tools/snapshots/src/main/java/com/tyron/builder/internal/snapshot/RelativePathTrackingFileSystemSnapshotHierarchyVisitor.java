package com.tyron.builder.internal.snapshot;

import com.tyron.builder.internal.RelativePathSupplier;

public interface RelativePathTrackingFileSystemSnapshotHierarchyVisitor {
    /**
     * Called before visiting the contents of a directory.
     */
    default void enterDirectory(DirectorySnapshot directorySnapshot, RelativePathSupplier relativePath) {}

    /**
     * Called for each regular file/directory/missing/unavailable file.
     *
     * @return how to continue visiting the rest of the snapshot hierarchy.
     */
    SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, RelativePathSupplier relativePath);

    /**
     * Called after all entries in the directory has been visited.
     */
    default void leaveDirectory(DirectorySnapshot directorySnapshot, RelativePathSupplier relativePath) {}
}
