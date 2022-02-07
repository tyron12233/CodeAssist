package com.tyron.builder.project.api;

import com.tyron.builder.project.listener.FileListener;

import java.io.File;
import java.util.Optional;

public interface FileManager {

    boolean isOpened(File file);

    void openFileForSnapshot(File file, String content);

    void setSnapshotContent(File file, String content, boolean notify);

    default void setSnapshotContent(File file, String content) {
        setSnapshotContent(file, content, true);
    }

    void closeFileForSnapshot(File file);

    void addSnapshotListener(FileListener listener);

    void removeSnapshotListener(FileListener listener);

    Optional<CharSequence> getFileContent(File file);

    void shutdown();
}
