package com.tyron.builder.api.file;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.provider.Provider;

import java.io.File;

/**
 * Represents a directory at some fixed location on the file system.
 * <p>
 * <b>Note:</b> This interface is not intended for implementation by build script or plugin authors. An instance of this class can be created
 * using the {@link #dir(String)} method or using various methods on {@link ProjectLayout} such as {@link ProjectLayout#getProjectDirectory()}.
 *
 * @since 4.1
 */
public interface Directory extends FileSystemLocation {
    /**
     * Returns the location of this directory, as an absolute {@link File}.
     *
     * @since 4.2
     */
    @Override
    File getAsFile();

    /**
     * Returns a {@link FileTree} that allows the files and directories contained in this directory to be queried.
     */
    FileTree getAsFileTree();

    /**
     * Returns a {@link Directory} whose location is the given path, resolved relative to this directory.
     *
     * @param path The path. Can be absolute.
     * @return The directory.
     */
    Directory dir(String path);

    /**
     * Returns a {@link Provider} whose value is a {@link Directory} whose location is the given path resolved relative to this directory.
     *
     * <p>The return value is live and the provided {@code path} is queried each time the return value is queried.
     *
     * @param path The path provider. Can have value that is an absolute path.
     * @return The provider.
     */
    Provider<Directory> dir(Provider<? extends CharSequence> path);

    /**
     * Returns a {@link RegularFile} whose location is the given path, resolved relative to this directory.
     *
     * @param path The path. Can be absolute.
     * @return The file.
     */
    RegularFile file(String path);

    /**
     * Returns a {@link Provider} whose value is a {@link RegularFile} whose location is the given path resolved relative to this directory.
     *
     * <p>The return value is live and the provided {@code path} is queried each time the return value is queried.
     *
     * @param path The path provider. Can have value that is an absolute path.
     * @return The file.
     */
    Provider<RegularFile> file(Provider<? extends CharSequence> path);

    /**
     * Returns a {@link FileCollection} containing the given files,
     * whose locations are the given paths resolved relative to this directory,
     * as defined by {@link BuildProject#files(Object...)}.
     *
     * This method can also be used to create an empty collection, but the collection may not be mutated later.
     *
     * @param paths The paths to the files. May be empty.
     * @return The file collection.
     * @since 6.0
     */
    FileCollection files(Object... paths);
}