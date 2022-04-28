package com.tyron.builder.compiler.incremental.resource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.net.URI;

/**
 * Used by {@link IncrementalAapt2Task} to find differences between two files
 */
public class ResourceFile extends File {

    public ResourceFile(@NonNull String pathname) {
        super(pathname);
    }

    public ResourceFile(@Nullable String parent, @NonNull String child) {
        super(parent, child);
    }

    public ResourceFile(@Nullable File parent, @NonNull String child) {
        super(parent, child);
    }

    public ResourceFile(@NonNull URI uri) {
        super(uri);
    }

    public static ResourceFile fromFile(File file) {
        return new ResourceFile(file.getAbsolutePath());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof ResourceFile) {
            return ((ResourceFile)obj).getName().equals(this.getName());
        }
        return false;
    }
}
