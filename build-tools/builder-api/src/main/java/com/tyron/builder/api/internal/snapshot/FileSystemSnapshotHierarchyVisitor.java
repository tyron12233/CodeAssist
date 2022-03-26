package com.tyron.builder.api.internal.snapshot;

/**
 * Visitor for {@link FileSystemSnapshot}.
 */
public interface FileSystemSnapshotHierarchyVisitor {

    /**
     * Called before visiting the contents of a directory.
     */
    default void enterDirectory(DirectorySnapshot directorySnapshot) {}

    /**
     * Called for each regular file/directory/missing/unavailable file.
     *
     * @return how to continue visiting the rest of the snapshot hierarchy.
     */
    SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot);

    /**
     * Called after all entries in the directory has been visited.
     */
    default void leaveDirectory(DirectorySnapshot directorySnapshot) {}

}