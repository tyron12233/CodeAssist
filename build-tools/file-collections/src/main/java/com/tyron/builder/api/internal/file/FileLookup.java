package com.tyron.builder.api.internal.file;

import com.tyron.builder.internal.file.PathToFileResolver;

import java.io.File;

/**
 * Provides access to various services to resolve and locate files.
 */
public interface FileLookup {
    /**
     * Returns a file resolver with no base directory.
     */
    FileResolver getFileResolver();

    /**
     * Returns a file resolver with no base directory.
     */
    PathToFileResolver getPathToFileResolver();

    /**
     * Returns a file resolver with the given base directory.
     */
    FileResolver getFileResolver(File baseDirectory);

    /**
     * Returns a file resolver with the given base directory.
     */
    PathToFileResolver getPathToFileResolver(File baseDirectory);
}
