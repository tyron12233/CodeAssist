package com.tyron.builder.api.internal.file.collections;

import com.google.common.collect.ImmutableSet;

import java.io.File;

import java.io.File;
import java.util.Set;

/**
 * Adapts a java util collection into a file set.
 */
public class ListBackedFileSet implements MinimalFileSet {
    private final ImmutableSet<File> files;

    public ListBackedFileSet(ImmutableSet<File> files) {
        this.files = files;
    }

    @Override
    public String getDisplayName() {
        switch (files.size()) {
            case 0:
                return "empty file collection";
            case 1:
                return String.format("file '%s'", files.iterator().next());
            default:
                return String.format("files %s", files);
        }
    }

    @Override
    public Set<File> getFiles() {
        return files;
    }
}