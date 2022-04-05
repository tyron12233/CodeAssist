package com.tyron.builder.api.internal.snapshot.impl;

import static com.tyron.builder.api.internal.snapshot.DirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy.INCLUDE_EMPTY_DIRS;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.RelativePathSupplier;
import com.tyron.builder.api.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.api.internal.snapshot.DirectorySnapshotBuilder;
import com.tyron.builder.api.internal.snapshot.FileSystemLeafSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.MerkleDirectorySnapshotBuilder;
import com.tyron.builder.api.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.api.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.api.internal.snapshot.RelativePathTracker;
import com.tyron.builder.api.internal.snapshot.RelativePathTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.api.internal.snapshot.SnapshotVisitResult;
import com.tyron.builder.api.internal.snapshot.SnapshottingFilter;

import java.util.concurrent.atomic.AtomicBoolean;

public class FileSystemSnapshotFilter {

    private FileSystemSnapshotFilter() {
    }

    public static FileSystemSnapshot filterSnapshot(SnapshottingFilter.FileSystemSnapshotPredicate predicate, FileSystemSnapshot unfiltered) {
        DirectorySnapshotBuilder builder = MerkleDirectorySnapshotBuilder.noSortingRequired();
        AtomicBoolean hasBeenFiltered = new AtomicBoolean(false);
        unfiltered.accept(new RelativePathTracker(), new FilteringVisitor(predicate, builder, hasBeenFiltered));
        if (builder.getResult() == null) {
            return FileSystemSnapshot.EMPTY;
        }
        return hasBeenFiltered.get() ? builder.getResult() : unfiltered;
    }

    private static class FilteringVisitor implements RelativePathTrackingFileSystemSnapshotHierarchyVisitor {
        private final SnapshottingFilter.FileSystemSnapshotPredicate predicate;
        private final DirectorySnapshotBuilder builder;
        private final AtomicBoolean hasBeenFiltered;

        public FilteringVisitor(SnapshottingFilter.FileSystemSnapshotPredicate predicate, DirectorySnapshotBuilder builder, AtomicBoolean hasBeenFiltered) {
            this.predicate = predicate;
            this.builder = builder;
            this.hasBeenFiltered = hasBeenFiltered;
        }

        @Override
        public void enterDirectory(DirectorySnapshot directorySnapshot, RelativePathSupplier relativePath) {
            builder.enterDirectory(directorySnapshot, INCLUDE_EMPTY_DIRS);
        }

        @Override
        public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, RelativePathSupplier relativePath) {
            boolean root = relativePath.isRoot();
            Iterable<String> relativePathForFiltering = root
                    ? ImmutableList.of(snapshot.getName())
                    : relativePath.getSegments();
            SnapshotVisitResult result;
            boolean forceInclude = snapshot.accept(new FileSystemLocationSnapshot.FileSystemLocationSnapshotTransformer<Boolean>() {
                @Override
                public Boolean visitDirectory(DirectorySnapshot directorySnapshot) {
                    return root;
                }

                @Override
                public Boolean visitRegularFile(RegularFileSnapshot fileSnapshot) {
                    return false;
                }

                @Override
                public Boolean visitMissing(MissingFileSnapshot missingSnapshot) {
                    return false;
                }
            });
            if (forceInclude || predicate.test(snapshot, relativePathForFiltering)) {
                if (snapshot instanceof FileSystemLeafSnapshot) {
                    builder.visitLeafElement((FileSystemLeafSnapshot) snapshot);
                }
                result = SnapshotVisitResult.CONTINUE;
            } else {
                hasBeenFiltered.set(true);
                result = SnapshotVisitResult.SKIP_SUBTREE;
            }
            return result;
        }

        @Override
        public void leaveDirectory(DirectorySnapshot directorySnapshot, RelativePathSupplier relativePath) {
            builder.leaveDirectory();
        }
    }
}