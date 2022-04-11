package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.RelativePath;

import java.io.File;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

public class AttributeBasedFileVisitDetails extends DefaultFileVisitDetails {
    private final BasicFileAttributes attributes;

    public AttributeBasedFileVisitDetails(File file, RelativePath relativePath, AtomicBoolean stop, Chmod chmod, Stat stat, BasicFileAttributes attributes) {
        super(file, relativePath, stop, chmod, stat);
        this.attributes = attributes;
    }

    @Override
    public long getSize() {
        return attributes.size();
    }

    @Override
    public long getLastModified() {
        return attributes.lastModifiedTime().toMillis();
    }
}