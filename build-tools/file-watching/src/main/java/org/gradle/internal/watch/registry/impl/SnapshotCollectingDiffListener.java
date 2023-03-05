package org.gradle.internal.watch.registry.impl;

import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemNode;
import org.gradle.internal.snapshot.SnapshotHierarchy;

import java.util.ArrayList;
import java.util.List;

public class SnapshotCollectingDiffListener implements SnapshotHierarchy.NodeDiffListener {
    private final List<FileSystemLocationSnapshot> removedSnapshots = new ArrayList<>();
    private final List<FileSystemLocationSnapshot> addedSnapshots = new ArrayList<>();

    public void publishSnapshotDiff(SnapshotHierarchy.SnapshotDiffListener snapshotDiffListener) {
        if (!removedSnapshots.isEmpty() || !addedSnapshots.isEmpty()) {
            snapshotDiffListener.changed(removedSnapshots, addedSnapshots);
        }
    }

    @Override
    public void nodeRemoved(FileSystemNode node) {
        node.rootSnapshots()
                .forEach(removedSnapshots::add);
    }

    @Override
    public void nodeAdded(FileSystemNode node) {
        node.rootSnapshots()
                .forEach(addedSnapshots::add);
    }
}
