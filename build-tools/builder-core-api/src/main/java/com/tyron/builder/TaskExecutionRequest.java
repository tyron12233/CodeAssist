package com.tyron.builder;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * A request to execute some tasks, along with an optional project path context to provide information necessary to select the tasks
 *
 * @since 2.0
 */
public interface TaskExecutionRequest {
    /**
     * The arguments to use to select and optionally configure the tasks, as if provided on the command-line.
     *
     * @return task name.
     */
    List<String> getArgs();

    /**
     * Project path associated with this task request if any.
     *
     * @return project path or {@code null} to use the default project path.
     */
    @Nullable
    String getProjectPath();

    /**
     * The root folder of the build that this task was defined in.
     *
     * @return the root project folder or {@code null} if the information is not available.
     * @since 3.3
     */
    @Nullable
    File getRootDir();
}