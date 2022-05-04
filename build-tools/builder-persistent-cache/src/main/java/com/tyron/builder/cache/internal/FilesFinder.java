package com.tyron.builder.cache.internal;

import java.io.File;
import java.io.FileFilter;

/**
 * Encapsulates a criteria for finding files.
 */
public interface FilesFinder {
    /**
     * Find files according to this finder's criteria within the supplied base
     * directory that pass the supplied {@link FileFilter}.
     */
    Iterable<File> find(File baseDir, FileFilter filter);
}
