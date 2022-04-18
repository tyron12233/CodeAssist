package com.tyron.builder.api.initialization;


import com.tyron.builder.api.tasks.TaskReference;

import java.io.File;

/**
 * A build that is included in the composite.
 *
 * @since 3.1
 */
public interface IncludedBuild {
    /**
     * The name of the included build.
     */
    String getName();

    /**
     * The root directory of the included build.
     */
    File getProjectDir();

    /**
     * Produces a reference to a task in the included build.
     */
    TaskReference task(String path);
}