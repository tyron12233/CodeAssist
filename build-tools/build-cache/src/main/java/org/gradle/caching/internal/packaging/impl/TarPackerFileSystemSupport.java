package org.gradle.caching.internal.packaging.impl;

import org.gradle.internal.file.TreeType;

import java.io.File;
import java.io.IOException;

public interface TarPackerFileSystemSupport {
    /**
     * Make sure the parent directory exists while the given file itself doesn't exist.
     */
    void ensureFileIsMissing(File entry) throws IOException;

    /**
     * Make sure directory exists.
     */
    void ensureDirectoryForTree(TreeType type, File root) throws IOException;
}