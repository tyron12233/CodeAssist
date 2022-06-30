package com.tyron.builder.internal.snapshot;

public abstract class RootTrackingFileSystemSnapshotHierarchyVisitor implements FileSystemSnapshotHierarchyVisitor {
    private int treeDepth;

    /**
     * Called before visiting the contents of a directory.
     */
    public void enterDirectory(DirectorySnapshot directorySnapshot, boolean isRoot) {}

    /**
     * Called for each regular file/directory/missing/unavailable file.
     *
     * @return how to continue visiting the rest of the snapshot hierarchy.
     */
    public abstract SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot);

    /**
     * Called after all entries in the directory has been visited.
     */
    public void leaveDirectory(DirectorySnapshot directorySnapshot, boolean isRoot) {}

    @Override
    public final void enterDirectory(DirectorySnapshot directorySnapshot) {
        enterDirectory(directorySnapshot, treeDepth == 0);
        treeDepth++;
    }

    @Override
    public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot) {
        return visitEntry(snapshot, treeDepth == 0);
    }

    @Override
    public final void leaveDirectory(DirectorySnapshot directorySnapshot) {
        treeDepth--;
        leaveDirectory(directorySnapshot, treeDepth == 0);
    }
}