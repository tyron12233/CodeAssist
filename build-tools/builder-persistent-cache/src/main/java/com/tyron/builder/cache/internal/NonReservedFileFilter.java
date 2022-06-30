package com.tyron.builder.cache.internal;

import java.io.File;
import java.io.FileFilter;
import java.util.Collection;

public class NonReservedFileFilter implements FileFilter {

    private final Collection<File> reservedFiles;

    public NonReservedFileFilter(Collection<File> reservedFiles) {
        this.reservedFiles = reservedFiles;
    }

    @Override
    public boolean accept(File file) {
        return !reservedFiles.contains(file);
    }

}
