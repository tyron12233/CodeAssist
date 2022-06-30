package com.tyron.builder.internal.vfs.impl;

import com.tyron.builder.internal.snapshot.SnapshotHierarchy;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

public class VfsRootReference {
    private volatile SnapshotHierarchy root;
    private final ReentrantLock updateLock = new ReentrantLock();

    public SnapshotHierarchy getRoot() {
        return root;
    }

    public VfsRootReference(SnapshotHierarchy root) {
        this.root = root;
    }

    public void update(UnaryOperator<SnapshotHierarchy> updateFunction) {
        updateLock.lock();
        try {
            SnapshotHierarchy currentRoot = root;
            root = updateFunction.apply(currentRoot);
        } finally {
            updateLock.unlock();
        }
    }
}