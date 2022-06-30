package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.internal.snapshot.DirectorySnapshotBuilder;
import com.tyron.builder.internal.snapshot.FileSystemLeafSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.MerkleDirectorySnapshotBuilder;
import com.tyron.builder.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * A {@link DirectorySnapshotBuilder} that tracks whether a directory has been filtered.
 *
 * You can mark a directory as filtered by {@link #markCurrentLevelAsFiltered()}.
 * When you do that, all the parent levels are marked as filtered as well.
 * On {@link #leaveDirectory()}, the {@code unfilteredSnapshotConsumer} will receive the direct child snapshots
 * of the left directory if it was marked as filtered, or nothing if it wasn't.
 * This builder delegates to {@link MerkleDirectorySnapshotBuilder} for the actual building of the snapshot.
 */
public class FilteredTrackingMerkleDirectorySnapshotBuilder implements DirectorySnapshotBuilder {
    private final Deque<Boolean> isCurrentLevelUnfiltered = new ArrayDeque<>();
    private final Consumer<FileSystemLocationSnapshot> unfilteredSnapshotConsumer;
    private final DirectorySnapshotBuilder delegate;

    public static FilteredTrackingMerkleDirectorySnapshotBuilder sortingRequired(Consumer<FileSystemLocationSnapshot> unfilteredSnapshotConsumer) {
        return new FilteredTrackingMerkleDirectorySnapshotBuilder(unfilteredSnapshotConsumer);
    }

    private FilteredTrackingMerkleDirectorySnapshotBuilder(Consumer<FileSystemLocationSnapshot> unfilteredSnapshotConsumer) {
        this.delegate = MerkleDirectorySnapshotBuilder.sortingRequired();
        this.unfilteredSnapshotConsumer = unfilteredSnapshotConsumer;
        // The root starts out as unfiltered.
        isCurrentLevelUnfiltered.addLast(true);
    }

    @Override
    public void enterDirectory(FileMetadata.AccessType accessType, String absolutePath, String name, DirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy) {
        isCurrentLevelUnfiltered.addLast(true);
        delegate.enterDirectory(accessType, absolutePath, name, emptyDirectoryHandlingStrategy);
    }

    @Override
    public void visitLeafElement(FileSystemLeafSnapshot snapshot) {
        delegate.visitLeafElement(snapshot);
    }

    @Override
    public void visitDirectory(DirectorySnapshot directorySnapshot) {
        delegate.visitDirectory(directorySnapshot);
    }

    public void markCurrentLevelAsFiltered() {
        isCurrentLevelUnfiltered.removeLast();
        isCurrentLevelUnfiltered.addLast(false);
    }

    public boolean isCurrentLevelUnfiltered() {
        return isCurrentLevelUnfiltered.getLast();
    }

    @Override
    public FileSystemLocationSnapshot leaveDirectory() {
        FileSystemLocationSnapshot directorySnapshot = delegate.leaveDirectory();
        boolean leftLevelUnfiltered = isCurrentLevelUnfiltered.removeLast();
        isCurrentLevelUnfiltered.addLast(isCurrentLevelUnfiltered.removeLast() && leftLevelUnfiltered);
        if (!leftLevelUnfiltered && directorySnapshot != null) {
            directorySnapshot.accept(new RootTrackingFileSystemSnapshotHierarchyVisitor() {

                @Override
                public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
                    if (isRoot) {
                        return SnapshotVisitResult.CONTINUE;
                    } else {
                        unfilteredSnapshotConsumer.accept(snapshot);
                    }

                    return SnapshotVisitResult.SKIP_SUBTREE;
                }
            });
        }
        return directorySnapshot;
    }

    @Nullable
    @Override
    public FileSystemLocationSnapshot getResult() {
        return delegate.getResult();
    }
}