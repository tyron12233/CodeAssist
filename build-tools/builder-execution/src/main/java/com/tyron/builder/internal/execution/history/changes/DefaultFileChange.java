package com.tyron.builder.internal.execution.history.changes;

import com.google.common.base.Objects;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.work.ChangeType;
import com.tyron.builder.work.FileChange;

import java.io.File;

public class DefaultFileChange implements Change, FileChange, InputFileDetails {
    private final String path;
    private final ChangeTypeInternal change;
    private final String title;
    private final FileType previousFileType;
    private final FileType currentFileType;
    private final String normalizedPath;

    public static DefaultFileChange added(String path, String title, FileType currentFileType, String normalizedPath) {
        return new DefaultFileChange(path, ChangeTypeInternal.ADDED, title, FileType.Missing, currentFileType, normalizedPath);
    }

    public static DefaultFileChange removed(String path, String title, FileType previousFileType, String normalizedPath) {
        return new DefaultFileChange(path, ChangeTypeInternal.REMOVED, title, previousFileType, FileType.Missing, normalizedPath);
    }

    public static DefaultFileChange modified(String path, String title, FileType previousFileType, FileType currentFileType, String normalizedPath) {
        return new DefaultFileChange(path, ChangeTypeInternal.MODIFIED, title, previousFileType, currentFileType, normalizedPath);
    }

    private DefaultFileChange(String path, ChangeTypeInternal change, String title, FileType previousFileType, FileType currentFileType, String normalizedPath) {
        this.path = path;
        this.change = change;
        this.title = title;
        this.previousFileType = previousFileType;
        this.currentFileType = currentFileType;
        this.normalizedPath = normalizedPath;
    }

    @Override
    public String getMessage() {
        return title + " file " + path + " " + getDisplayedChangeType().describe() + ".";
    }

    private ChangeTypeInternal getDisplayedChangeType() {
        if (change != ChangeTypeInternal.MODIFIED) {
            return change;
        }
        if (previousFileType == FileType.Missing) {
            return ChangeTypeInternal.ADDED;
        }
        if (currentFileType == FileType.Missing) {
            return ChangeTypeInternal.REMOVED;
        }
        return ChangeTypeInternal.MODIFIED;
    }

    @Override
    public String toString() {
        return getMessage();
    }

    public String getPath() {
        return path;
    }

    @Override
    public File getFile() {
        return new File(path);
    }

    @Override
    public ChangeType getChangeType() {
        return change.getPublicType();
    }

    @Override
    public com.tyron.builder.api.file.FileType getFileType() {
        FileType typeToConvert = change == ChangeTypeInternal.REMOVED ? previousFileType : currentFileType;
        return toPublicFileType(typeToConvert);
    }

    public static com.tyron.builder.api.file.FileType toPublicFileType(FileType fileType) {
        switch (fileType) {
            case RegularFile:
                return com.tyron.builder.api.file.FileType.FILE;
            case Directory:
                return com.tyron.builder.api.file.FileType.DIRECTORY;
            case Missing:
                return com.tyron.builder.api.file.FileType.MISSING;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public String getNormalizedPath() {
        return normalizedPath;
    }

    public ChangeTypeInternal getType() {
        return change;
    }

    @Override
    public boolean isAdded() {
        return change == ChangeTypeInternal.ADDED;
    }

    @Override
    public boolean isModified() {
        return change == ChangeTypeInternal.MODIFIED;
    }

    @Override
    public boolean isRemoved() {
        return change == ChangeTypeInternal.REMOVED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultFileChange that = (DefaultFileChange) o;
        return Objects.equal(path, that.path)
               && change == that.change
               && Objects.equal(title, that.title)
               && Objects.equal(previousFileType, that.previousFileType)
               && Objects.equal(currentFileType, that.currentFileType)
               && Objects.equal(normalizedPath, that.normalizedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(path, change, title, previousFileType, currentFileType, normalizedPath);
    }
}