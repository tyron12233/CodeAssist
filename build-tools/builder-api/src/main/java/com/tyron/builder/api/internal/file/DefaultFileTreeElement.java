package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public class DefaultFileTreeElement extends AbstractFileTreeElement {
    private final File file;
    private final RelativePath relativePath;
    private final Stat stat;

    public DefaultFileTreeElement(File file, RelativePath relativePath, Chmod chmod, Stat stat) {
        super(chmod);
        this.file = file;
        this.relativePath = relativePath;
        this.stat = stat;
    }

    public static DefaultFileTreeElement of(File file, FileSystem fileSystem) {
        RelativePath path = RelativePath.parse(!file.isDirectory(), file.getAbsolutePath());
        return new DefaultFileTreeElement(file, path, fileSystem, fileSystem);
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public String getDisplayName() {
        return "file '" + file + "'";
    }

    @Override
    public long getLastModified() {
        return file.lastModified();
    }

    @Override
    public long getSize() {
        return file.length();
    }

    @Override
    public boolean isDirectory() {
        return !relativePath.isFile();
    }

    @Override
    public InputStream open() {
        try {
            return FileUtils.openInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public RelativePath getRelativePath() {
        return relativePath;
    }

    @Override
    public int getMode() {
        return stat.getUnixMode(file);
    }
}