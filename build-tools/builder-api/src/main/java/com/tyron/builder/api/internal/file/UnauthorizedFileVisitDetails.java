package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.RelativePath;

import java.io.File;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public class UnauthorizedFileVisitDetails implements FileVisitDetails {
    private File file;
    private RelativePath relativePath;

    public UnauthorizedFileVisitDetails(File file, RelativePath relativePath) {
        this.file = file;
        this.relativePath = relativePath;
    }

    @Override
    public void stopVisiting() {
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public boolean isDirectory() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLastModified() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream open() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyTo(OutputStream output) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean copyTo(File target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public String getPath() {
        return getRelativePath().getPathString();
    }

    @Override
    public RelativePath getRelativePath() {
        return relativePath;
    }

    @Override
    public int getMode() {
        throw new UnsupportedOperationException();
    }
}