package com.tyron.builder.api.file;

/**
 * Provides access to details about a file or directory being visited by a {@link FileVisitor}.
 *
 * @see FileTree#visit(groovy.lang.Closure)
 */
public interface FileVisitDetails extends FileTreeElement {

    /**
     * Requests that file visiting terminate after the current file.
     */
    void stopVisiting();
}