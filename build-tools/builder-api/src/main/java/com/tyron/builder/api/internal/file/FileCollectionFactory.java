package com.tyron.builder.api.internal.file;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.tasks.TaskDependency;

import java.io.File;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

public interface FileCollectionFactory {
    /**
     * Creates a copy of this factory that uses the given resolver to convert various types to File instances.
     */
    FileCollectionFactory withResolver(PathToFileResolver fileResolver);

    /**
     * Creates a {@link FileCollection} with the given contents.
     *
     * The collection is live, so that the contents are queried as required on query of the collection.
     */
    FileCollectionInternal create(MinimalFileSet contents);

    /**
     * Creates a {@link FileCollection} with the given contents, and built by the given tasks.
     *
     * The collection is live, so that the contents are queried as required on query of the collection.
     */
    FileCollectionInternal create(TaskDependency builtBy, MinimalFileSet contents);

    /**
     * Creates an empty {@link FileCollection}
     */
    FileCollectionInternal empty(String displayName);

    /**
     * Creates an empty {@link FileCollection}
     */
    FileCollectionInternal empty();

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is not live. The provided array is queried on construction and discarded.
     */
    FileCollectionInternal fixed(File... files);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is not live. The provided {@link Collection} is queried on construction and discarded.
     */
    FileCollectionInternal fixed(Collection<File> files);

    /**
     * Creates a {@link FileCollection} with the given files as content. The result is not live and does not reflect changes to the array.
     *
     * <p>The collection is not live. The provided array is queried on construction and discarded.
     */
    FileCollectionInternal fixed(String displayName, File... files);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is not live. The provided {@link Collection} is queried on construction and discarded.
     */
    FileCollectionInternal fixed(String displayName, Collection<File> files);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is live and resolves the files on each query.
     *
     * <p>The collection fails to resolve if it contains providers which are not present.
     */
    FileCollectionInternal resolving(String displayName, Object sources);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is live and resolves the files on each query.
     *
     * <p>The collection ignores providers which are not present.
     */
    FileCollectionInternal resolvingLeniently(String displayName, Object sources);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is live and resolves the files on each query.
     *
     * <p>The collection fails to resolve if it contains providers which are not present.
     */
    FileCollectionInternal resolving(Object sources);

    /**
     * Creates a {@link FileCollection} with the given files as content.
     *
     * <p>The collection is live and resolves the files on each query.
     *
     * <p>The collection ignores providers which are not present.
     */
    FileCollectionInternal resolvingLeniently(Object sources);

    /**
     * Creates an empty {@link ConfigurableFileCollection} instance.
     */
    ConfigurableFileCollection configurableFiles(String displayName);

    /**
     * Creates an empty {@link ConfigurableFileCollection} instance.
     */
    ConfigurableFileCollection configurableFiles();

    /**
     * Creates a {@link ConfigurableFileTree} instance with no base dir specified.
     */
    ConfigurableFileTree fileTree();

    /**
     * Creates a file tree containing the given generated file.
     */
    FileTreeInternal generated(Factory<File> tmpDir, String fileName, Action<File> fileGenerationListener, Action<OutputStream> contentGenerator);

    /**
     * Creates a file tree made up of the union of the given trees.
     *
     * <p>The tree is not live. The provided list is queried on construction and discarded.
     */
    FileTreeInternal treeOf(List<? extends FileTreeInternal> fileTrees);

    FileTreeInternal treeOf(MinimalFileTree tree);
}