package com.tyron.builder.internal.snapshot;

import com.google.common.collect.ImmutableList;

import java.util.Collection;
import java.util.List;

public class CompositeFileSystemSnapshot implements FileSystemSnapshot {
    private final ImmutableList<FileSystemSnapshot> snapshots;

    private CompositeFileSystemSnapshot(Collection<? extends FileSystemSnapshot> snapshots) {
        this.snapshots = ImmutableList.copyOf(snapshots);
    }

    public static FileSystemSnapshot of(List<? extends FileSystemSnapshot> snapshots) {
        switch (snapshots.size()) {
            case 0:
                return EMPTY;
            case 1:
                return snapshots.get(0);
            default:
                return new CompositeFileSystemSnapshot(snapshots);
        }
    }

    @Override
    public SnapshotVisitResult accept(FileSystemSnapshotHierarchyVisitor visitor) {
        for (FileSystemSnapshot snapshot : snapshots) {
            SnapshotVisitResult result = snapshot.accept(visitor);
            if (result == SnapshotVisitResult.TERMINATE) {
                return SnapshotVisitResult.TERMINATE;
            }
        }
        return SnapshotVisitResult.CONTINUE;
    }

    @Override
    public SnapshotVisitResult accept(RelativePathTracker pathTracker, RelativePathTrackingFileSystemSnapshotHierarchyVisitor visitor) {
        for (FileSystemSnapshot snapshot : snapshots) {
            SnapshotVisitResult result = snapshot.accept(pathTracker, visitor);
            if (result == SnapshotVisitResult.TERMINATE) {
                return SnapshotVisitResult.TERMINATE;
            }
        }
        return SnapshotVisitResult.CONTINUE;
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CompositeFileSystemSnapshot that = (CompositeFileSystemSnapshot) o;

        return snapshots.equals(that.snapshots);
    }

    @Override
    public int hashCode() {
        return snapshots.hashCode();
    }

    @Override
    public String toString() {
        return snapshots.toString();
    }
}