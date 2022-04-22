package com.tyron.builder.api.file;

/**
 * <p>A {@code FileVisitor} is used to visit each of the files in a {@link FileTree}.</p>
 */
public interface FileVisitor {
    /**
     * Visits a directory.
     *
     * @param dirDetails Meta-info about the directory.
     */
    void visitDir(FileVisitDetails dirDetails);

    /**
     * Visits a file.
     *
     * @param fileDetails Meta-info about the file.
     */
    void visitFile(FileVisitDetails fileDetails);
}