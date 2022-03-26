package com.tyron.builder.api.file;

import com.tyron.builder.api.tasks.Buildable;
import com.tyron.builder.api.tasks.util.PatternFilterable;

import java.io.File;
import java.util.Set;

/**
 * <p>A {@link FileTree} with a single base directory, which can be configured and modified.</p>
 *
 * <p>You can obtain a {@code ConfigurableFileTree} instance by calling {@link org.gradle.api.Project#fileTree(java.util.Map)}.</p>
 */
public interface ConfigurableFileTree extends FileTree, DirectoryTree, PatternFilterable, Buildable {
    /**
     * Specifies base directory for this file tree using the given path. The path is evaluated as per {@link
     * org.gradle.api.Project#file(Object)}.
     *
     * @param dir The base directory.
     * @return this
     */
    ConfigurableFileTree from(Object dir);

    /**
     * Returns the base directory of this file tree.
     *
     * @return The base directory. Never returns null.
     */
    @Override
    File getDir();

    /**
     * Specifies base directory for this file tree using the given path. The path is evaluated as per {@link
     * org.gradle.api.Project#file(Object)}.
     *
     * @param dir The base directory.
     * @return this
     */
    ConfigurableFileTree setDir(Object dir);

    /**
     * Returns the set of tasks which build the files of this collection.
     *
     * @return The set. Returns an empty set when there are no such tasks.
     */
    Set<Object> getBuiltBy();

    /**
     * Sets the tasks which build the files of this collection.
     *
     * @param tasks The tasks. These are evaluated as per {@link org.gradle.api.Task#dependsOn(Object...)}.
     * @return this
     */
    ConfigurableFileTree setBuiltBy(Iterable<?> tasks);

    /**
     * Registers some tasks which build the files of this collection.
     *
     * @param tasks The tasks. These are evaluated as per {@link org.gradle.api.Task#dependsOn(Object...)}.
     * @return this
     */
    ConfigurableFileTree builtBy(Object... tasks);
}