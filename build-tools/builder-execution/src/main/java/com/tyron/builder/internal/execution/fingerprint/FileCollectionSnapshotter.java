package com.tyron.builder.internal.execution.fingerprint;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

/**
 * Service for snapshotting {@link FileCollection}s.
 */
public interface FileCollectionSnapshotter {
    interface Result {
        FileSystemSnapshot getSnapshot();

        /**
         * Whether the snapshot file collection consists only of file trees.
         *
         * If the file collection does not contain any file trees, then this will return {@code false}.
         */
        boolean isFileTreeOnly();

        /**
         * Whether any of the snapshot file collections is an archive tree backed by a file.
         */
        boolean containsArchiveTrees();
    }

    /**
     * Snapshot the roots of a file collection.
     */
    Result snapshot(FileCollection fileCollection);
}
