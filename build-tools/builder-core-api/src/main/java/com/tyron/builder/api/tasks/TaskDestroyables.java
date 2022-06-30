package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

/**
 * Represents the files or directories that a {@link Task} destroys (removes).
 *
 * @since 4.0
 */
public interface TaskDestroyables {
    /**
     * Registers files or directories that this task destroys.
     *
     * @param paths The files or directories that will be destroyed. The given paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     *
     * @since 4.3
     */
    void register(Object... paths);
}