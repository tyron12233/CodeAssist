package com.tyron.builder.internal.execution.history.impl;

import static com.tyron.builder.internal.snapshot.SnapshotUtil.getRootHashes;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot.FileSystemLocationSnapshotTransformer;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotUtil;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class DefaultOverlappingOutputDetector implements OverlappingOutputDetector {
    @Override
    @Nullable
    public OverlappingOutputs detect(ImmutableSortedMap<String, FileSystemSnapshot> previous, ImmutableSortedMap<String, FileSystemSnapshot> current) {
        for (Map.Entry<String, FileSystemSnapshot> entry : current.entrySet()) {
            String propertyName = entry.getKey();
            FileSystemSnapshot currentSnapshot = entry.getValue();
            FileSystemSnapshot previousSnapshot = previous.getOrDefault(propertyName, FileSystemSnapshot.EMPTY);
            // If the root hashes are the same there are no overlapping outputs
            if (getRootHashes(previousSnapshot).equals(getRootHashes(currentSnapshot))) {
                continue;
            }
            OverlappingOutputs overlappingOutputs = detect(propertyName, previousSnapshot, currentSnapshot);
            if (overlappingOutputs != null) {
                return overlappingOutputs;
            }
        }
        return null;
    }

    @Nullable
    private static OverlappingOutputs detect(String propertyName, FileSystemSnapshot previous, FileSystemSnapshot before) {
        Map<String, FileSystemLocationSnapshot> previousIndex = SnapshotUtil.index(previous);
        OverlappingOutputsDetectingVisitor outputsDetectingVisitor = new OverlappingOutputsDetectingVisitor(previousIndex);
        before.accept(outputsDetectingVisitor);
        String overlappingPath = outputsDetectingVisitor.getOverlappingPath();
        return overlappingPath == null ? null : new OverlappingOutputs(propertyName, overlappingPath);
    }

    private static class OverlappingOutputsDetectingVisitor extends RootTrackingFileSystemSnapshotHierarchyVisitor {
        private final Map<String, FileSystemLocationSnapshot> previousSnapshots;
        private String overlappingPath;

        public OverlappingOutputsDetectingVisitor(Map<String, FileSystemLocationSnapshot> previousSnapshots) {
            this.previousSnapshots = previousSnapshots;
        }

        @Override
        public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
            boolean newContent = snapshot.accept(new FileSystemLocationSnapshotTransformer<Boolean>() {
                @Override
                public Boolean visitDirectory(DirectorySnapshot directorySnapshot) {
                    // Check if a new directory appeared. For matching directories don't check content
                    // hash as we should detect individual entries that are different instead)
                    return hasNewContent(directorySnapshot);
                }

                @Override
                public Boolean visitRegularFile(RegularFileSnapshot fileSnapshot) {
                    // Check if a new file has appeared, or if an existing file's content has changed
                    return hasNewContent(fileSnapshot);
                }

                @Override
                public Boolean visitMissing(MissingFileSnapshot missingSnapshot) {
                    // If the root has gone missing then we don't have overlaps
                    if (isRoot) {
                        return false;
                    }
                    // Otherwise check for newly added broken symlinks and unreadable files
                    return hasNewContent(missingSnapshot);
                }
            });
            if (newContent) {
                overlappingPath = snapshot.getAbsolutePath();
                return SnapshotVisitResult.TERMINATE;
            } else {
                return SnapshotVisitResult.CONTINUE;
            }
        }

        private boolean hasNewContent(FileSystemLocationSnapshot snapshot) {
            FileSystemLocationSnapshot previousSnapshot = previousSnapshots.get(snapshot.getAbsolutePath());
            // Created since last execution, possibly by another task
            if (previousSnapshot == null) {
                return true;
            }
            return !snapshot.isContentUpToDate(previousSnapshot);
        }

        @Nullable
        public String getOverlappingPath() {
            return overlappingPath;
        }
    }
}