package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileSystemLocation;

import java.io.File;

public class DefaultFileSystemLocation implements FileSystemLocation {
    private final File file;

    public DefaultFileSystemLocation(File file) {
        this.file = file;
    }

    @Override
    public File getAsFile() {
        return file;
    }

    @Override
    public String toString() {
        return file.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        DefaultFileSystemLocation other = (DefaultFileSystemLocation) obj;
        return other.file.equals(file);
    }

    @Override
    public int hashCode() {
        return file.hashCode();
    }
}