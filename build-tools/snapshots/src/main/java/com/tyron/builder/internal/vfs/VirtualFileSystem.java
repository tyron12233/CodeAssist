package com.tyron.builder.internal.vfs;

import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.MetadataSnapshot;

import java.util.Optional;

public interface VirtualFileSystem {

    /**
     * Returns the snapshot stored at the absolute path if it exists in the VFS.
     */
    Optional<FileSystemLocationSnapshot> findSnapshot(String absolutePath);

    /**
     * Returns the metadata stored at the absolute path if it exists.
     */
    Optional<MetadataSnapshot> findMetadata(String absolutePath);

    /**
     * Adds the information of the snapshot at the absolute path to the VFS.
     */
    void store(String absolutePath, FileSystemLocationSnapshot snapshot);

    /**
     * Removes any information at the absolute paths from the VFS.
     */
    void invalidate(Iterable<String> locations);

    /**
     * Removes any information from the VFS.
     */
    void invalidateAll();

}