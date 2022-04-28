package com.tyron.builder.project.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.listener.FileListener;

import java.io.File;
import java.time.Instant;
import java.util.Optional;

public interface FileManager {

    /**
     * @param file The file
     * @return Whether this file manager has the file opened.
     */
    boolean isOpened(@NonNull File file);

    @Nullable
    Instant getLastModified(@NonNull File file);

    void setLastModified(@NonNull File file, Instant instant);

    /**
     * Open the file and save its content in memory
     * @param file The file
     * @param content The contents of the file
     */
    void openFileForSnapshot(@NonNull File file, String content);

    /**
     * Sets the file content without notifying the passed FileListener
     * @param file The file
     * @param content The contents
     * @param listener The listner to ignore
     */
    void setSnapshotContent(@NonNull File file, String content, FileListener listener);

    /**
     * Change the stored contents of this file only if it has been opened before
     * through {@link FileManager#openFileForSnapshot(File, String)}
     * @param file The file to be opened
     * @param content The contents of the file
     * @param notify whether listeners should be notified
     */
    void setSnapshotContent(@NonNull File file, String content, boolean notify);

    default void setSnapshotContent(File file, String content) {
        setSnapshotContent(file, content, true);
    }

    /**
     * Mark the file as closed and save its stored snapshot to disk
     * @param file the file to be saved
     */
    void closeFileForSnapshot(@NonNull File file);

    void addSnapshotListener(FileListener listener);

    void removeSnapshotListener(FileListener listener);

    /**
     * Get the contents of the file stored in memory. If the file is not yet opened,
     * This returns {@link Optional#empty()}
     * @param file The file
     * @return The contents stored in memory
     */
    Optional<CharSequence> getFileContent(File file);

    /**
     * Instructs the file manager to release resources and save all the opened files to disk.
     */
    void shutdown();

    void saveContents();
}
