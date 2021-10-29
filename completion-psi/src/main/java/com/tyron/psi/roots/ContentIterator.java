package com.tyron.psi.roots;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

/**
 * Interface which can be used to receive the contents of a project.
 *
 * @see FileIndex#iterateContent(ContentIterator)
 */
@FunctionalInterface
public interface ContentIterator {
    /**
     * Processes the specified file or directory.
     *
     * @param fileOrDir the file or directory to process.
     * @return false if files processing should be stopped, true if it should be continued.
     */
    boolean processFile(@NotNull VirtualFile fileOrDir);
}
