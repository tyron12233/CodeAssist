package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.internal.file.FileType;

/**
 * The absolute path and the type of a file.
 *
 * Used to construct {@link DefaultFileChange}s.
 */
public class FilePathWithType {
    private final String absolutePath;
    private final FileType fileType;

    public FilePathWithType(String absolutePath, FileType fileType) {
        this.absolutePath = absolutePath;
        this.fileType = fileType;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public FileType getFileType() {
        return fileType;
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", fileType, absolutePath);
    }
}
