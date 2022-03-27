package com.tyron.builder.api.internal.snapshot;

public class RelativePathTrackingFileSystemSnapshotHierarchyVisitor {
    public SnapshotVisitResult visitEntry(FileSystemSnapshot fileSystemLeafSnapshot,
                                          RelativePathTracker pathTracker) {
        return null;
    }

    public void enterDirectory(DirectorySnapshot directorySnapshot,
                               RelativePathTracker pathTracker) {
    }

    public void leaveDirectory(DirectorySnapshot directorySnapshot,
                               RelativePathTracker pathTracker) {

    }
}
