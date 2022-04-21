package com.tyron.builder.internal.vfs.impl;


import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.MetadataSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.vfs.VirtualFileSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public abstract class AbstractVirtualFileSystem implements VirtualFileSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractVirtualFileSystem.class);

    protected final VfsRootReference rootReference;

    protected AbstractVirtualFileSystem(VfsRootReference rootReference) {
        this.rootReference = rootReference;
    }

    @Override
    public Optional<FileSystemLocationSnapshot> findSnapshot(String absolutePath) {
        return rootReference.getRoot().findSnapshot(absolutePath);
    }

    @Override
    public Optional<MetadataSnapshot> findMetadata(String absolutePath) {
        return rootReference.getRoot().findMetadata(absolutePath);
    }

    @Override
    public void store(String absolutePath, FileSystemLocationSnapshot snapshot) {
        rootReference.update(root -> updateNotifyingListeners(diffListener -> root.store(absolutePath, snapshot, diffListener)));
    }

    @Override
    public void invalidate(Iterable<String> locations) {
        LOGGER.debug("Invalidating VFS paths: " + locations);
        rootReference.update(root -> {
            SnapshotHierarchy result = root;
            for (String location : locations) {
                SnapshotHierarchy currentRoot = result;
                result = updateNotifyingListeners(diffListener -> currentRoot.invalidate(location, diffListener));
            }
            return result;
        });
    }

    @Override
    public void invalidateAll() {
        LOGGER.debug("Invalidating the whole VFS");
        rootReference.update(root -> updateNotifyingListeners(diffListener -> {
            root.rootSnapshots()
                    .forEach(diffListener::nodeRemoved);
            return root.empty();
        }));
    }

    /**
     * Runs a single update on a {@link SnapshotHierarchy} and notifies the currently active listeners after the update.
     */
    protected abstract SnapshotHierarchy updateNotifyingListeners(UpdateFunction updateFunction);

    public interface UpdateFunction {
        /**
         * Runs a single update on a {@link SnapshotHierarchy}, notifying the diffListener about changes.
         *
         * @return updated ${@link SnapshotHierarchy}.
         */
        SnapshotHierarchy update(SnapshotHierarchy.NodeDiffListener diffListener);
    }
}