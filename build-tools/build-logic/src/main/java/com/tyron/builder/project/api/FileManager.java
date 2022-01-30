package com.tyron.builder.project.api;

import java.io.File;
import java.util.Optional;

public interface FileManager {

    void openFileForSnapshot(File file, String content);

    void setSnapshotContent(File file, String content);

    void closeFileForSnapshot(File file);

    Optional<CharSequence> getFileContent(File file);

    void shutdown();
}
