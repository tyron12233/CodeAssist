package com.tyron.builder.internal.snapshot;

import com.tyron.builder.internal.file.FileMetadata;

import org.jetbrains.annotations.Nullable;

/**
 * A builder for {@link DirectorySnapshot}.
 *
 * In order to build a directory snapshot, you need to call the methods for entering/leaving a directory
 * and for visiting leaf elements.
 * The visit methods need to be called in depth-first order.
 * When leaving a directory, the builder will create a {@link DirectorySnapshot} for the directory,
 * calculating the combined hash of the entries.
 */
public interface DirectorySnapshotBuilder {

    /**
     * Convenience method for {@link #enterDirectory(FileMetadata.AccessType, String, String, EmptyDirectoryHandlingStrategy)}
     * when you already have a {@link DirectorySnapshot}.
     */
    default void enterDirectory(DirectorySnapshot directorySnapshot, EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy) {
        enterDirectory(directorySnapshot.getAccessType(), directorySnapshot.getAbsolutePath(), directorySnapshot.getName(), emptyDirectoryHandlingStrategy);
    }

    /**
     * Method to call before visiting all the entries of a directory.
     */
    void enterDirectory(FileMetadata.AccessType accessType, String absolutePath, String name, EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy);

    void visitLeafElement(FileSystemLeafSnapshot snapshot);

    void visitDirectory(DirectorySnapshot directorySnapshot);

    /**
     * Method to call after having visited all the entries of a directory.
     *
     * May return {@code null} when the directory is empty and {@link EmptyDirectoryHandlingStrategy#EXCLUDE_EMPTY_DIRS}
     * has been used when calling {@link #enterDirectory(FileMetadata.AccessType, String, String, EmptyDirectoryHandlingStrategy)}.
     * This means that the directory will not be part of the built snapshot.
     */
    @Nullable
    FileSystemLocationSnapshot leaveDirectory();

    /**
     * Returns the snapshot for the root directory.
     *
     * May return null if
     * - nothing was visited, or
     * - only empty directories have been visited with {@link EmptyDirectoryHandlingStrategy#EXCLUDE_EMPTY_DIRS}.
     */
    @Nullable
    FileSystemLocationSnapshot getResult();

    enum EmptyDirectoryHandlingStrategy {
        INCLUDE_EMPTY_DIRS,
        EXCLUDE_EMPTY_DIRS
    }
}