package org.gradle.api.internal.file.collections;

import org.gradle.api.file.FileVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.tasks.util.PatternSet;

import java.io.File;

/**
 * A minimal file tree implementation. An implementation can optionally also implement the following interfaces:
 *
 * <ul>
 *  <li>{@link FileSystemMirroringFileTree}</li>
 *  <li>{@link LocalFileTree}</li>
 *  <li>{@link PatternFilterableFileTree}</li>
 * </ul>
 */
public interface MinimalFileTree extends MinimalFileCollection {
    /**
     * Visits the elements of this tree, in depth-first prefix order.
     */
    void visit(FileVisitor visitor);

    void visitStructure(MinimalFileTreeStructureVisitor visitor, FileTreeInternal owner);

    interface MinimalFileTreeStructureVisitor {

        /**
         * Visits a file tree at a root file on the file system (potentially filtered).
         */
        void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree);

        /**
         * Visits a file tree whose content is generated from the contents of a file.
         */
        void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree);
    }
}