package com.tyron.builder.api.file;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.provider.Provider;

import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Represents some configurable directory location, whose value is mutable.
 *
 * <p>
 * You can create a {@link DirectoryProperty} using {@link ObjectFactory#directoryProperty()}.
 * </p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.</p>
 *
 * @since 4.3
 */
public interface DirectoryProperty extends FileSystemLocationProperty<Directory> {
    /**
     * Returns a {@link FileTree} that allows the files and directories contained in this directory to be queried.
     */
    FileTree getAsFileTree();

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty value(@Nullable Directory value);

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty value(Provider<? extends Directory> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty fileValue(@Nullable File file);

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty fileProvider(Provider<File> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty convention(Directory value);

    /**
     * {@inheritDoc}
     */
    @Override
    DirectoryProperty convention(Provider<? extends Directory> provider);

    /**
     * Returns a {@link Directory} whose value is the given path resolved relative to the value of this directory.
     *
     * @param path The path. Can be absolute.
     * @return The directory.
     */
    Provider<Directory> dir(String path);

    /**
     * Returns a {@link Directory} whose value is the given path resolved relative to the value of this directory.
     *
     * @param path The path. Can have a value that is an absolute path.
     * @return The directory.
     */
    Provider<Directory> dir(Provider<? extends CharSequence> path);

    /**
     * Returns a {@link RegularFile} whose value is the given path resolved relative to the value of this directory.
     *
     * @param path The path. Can be absolute.
     * @return The file.
     */
    Provider<RegularFile> file(String path);

    /**
     * Returns a {@link RegularFile} whose value is the given path resolved relative to the value of this directory.
     *
     * @param path The path. Can have a value that is an absolute path.
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