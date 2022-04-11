package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.providers.HasConfigurableValue;

import java.util.Set;

/**
 * <p>A {@code ConfigurableFileCollection} is a mutable {@code FileCollection}.</p>
 *
 * <p>You can obtain an instance of {@code ConfigurableFileCollection} by calling {@link org.gradle.api.Project#files(Object...)} or {@link ObjectFactory#fileCollection()}.</p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.</p>
 */
public interface ConfigurableFileCollection extends FileCollection, HasConfigurableValue {
    /**
     * Returns the set of source paths for this collection. The paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     *
     * @return The set of source paths. Returns an empty set if none.
     */
    Set<Object> getFrom();

    /**
     * Sets the source paths for this collection. The given paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     *
     * @param paths The paths.
     */
    void setFrom(Iterable<?> paths);

    /**
     * Sets the source paths for this collection. The given paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     *
     * @param paths The paths.
     */
    void setFrom(Object... paths);

    /**
     * Adds a set of source paths to this collection. The given paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     *
     * @param paths The files to add.
     * @return this
     */
    ConfigurableFileCollection from(Object... paths);

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
    ConfigurableFileCollection setBuiltBy(Iterable<?> tasks);

    /**
     * Registers some tasks which build the files of this collection.
     *
     * @param tasks The tasks. These are evaluated as per {@link org.gradle.api.Task#dependsOn(Object...)}.
     * @return this
     */
    ConfigurableFileCollection builtBy(Object... tasks);
}