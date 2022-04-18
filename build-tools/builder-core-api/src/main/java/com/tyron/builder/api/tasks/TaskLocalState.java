package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

/**
 * Represents the files or directories that represent the local state of a {@link Task}.
 * The typical usage for local state is to store non-relocatable incremental analysis between builds.
 * Local state is removed whenever the task is loaded from cache.
 *
 * @since 4.3
 */
public interface TaskLocalState {
    /**
     * Registers files and directories as local state of this task.
     *
     * @param paths The files that represent the local state. The given paths are evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     */
    void register(Object... paths);
}