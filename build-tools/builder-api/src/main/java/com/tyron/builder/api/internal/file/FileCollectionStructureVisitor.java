package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

/**
 * Used with {@link FileCollectionInternal#visitStructure(FileCollectionStructureVisitor)} this visitor
 * is called for each element in a file collection that is an atomic source of files.
 */
public interface FileCollectionStructureVisitor {
    enum VisitType {
        // Visitor is interested in the contents of the collection
        Visit,
        // Visitor is not interested in the contents of the collection, but would like to receive the source and other metadata
        NoContents,
        // Visitor is interested in the spec of the collection - that is, the files that the collection <em>might</em> include in the future
        // For most collections, this will be the same as the elements of the collection. However, for a collection that includes
        // all of the files from a directory, the spec for the collection would be the directory + the patterns it matches files with
        // Or, for a collection that contains some transformation of another collection, the spec for the collection would include the spec
        // for the original collection
        Spec
    }

    /**
     * Called when starting to visit a file collection. Can return true to continue with visiting or false to skip this collection and its contents.
     *
     * <p>When a file collection represents a container of file collections, the children of the file collection are visited in order. Visiting a child works in the
     * same way as visiting this collection, starting with a call to {@link #startVisit(FileCollectionInternal.Source, FileCollectionInternal)}.
     *
     * <p>When the file collection contains some other source of files then {@link #prepareForVisit(FileCollectionInternal.Source)} is called for each source in order.
     */
    default boolean startVisit(FileCollectionInternal.Source source, FileCollectionInternal fileCollection) {
        return true;
    }

    /**
     * Called prior to visiting a file source with the given spec, and allows this visitor to skip these files.
     * A "file source" is some opaque source of files that is not a full {@link FileCollection}.
     *
     * <p>Note that this method is not necessarily called immediately before one of the visit methods, as some collections may be
     * resolved in parallel. However, all visiting is performed sequentially and in order.
     * This method is also called sequentially and in order.
     *
     * @return how should the collection be visited?
     */
    default VisitType prepareForVisit(FileCollectionInternal.Source source) {
        return VisitType.Visit;
    }

    /**
     * Visits an opaque file source that cannot be visited in further detail.
     */
    void visitCollection(FileCollectionInternal.Source source, Iterable<File> contents);

    /**
     * Visits a file tree whose content is generated from some opaque source.
     */
    void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree);

    /**
     * Visits a file tree at a root file on the file system (potentially filtered).
     */
    void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree);

    /**
     * Visits a file tree whose content is generated from the contents of a file.
     */
    void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree);
}