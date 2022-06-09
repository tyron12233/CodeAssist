package com.tyron.builder.api.initialization.resolve;

import com.tyron.builder.api.Incubating;

/**
 * The repository mode configures how repositories are setup in the build.
 *
 * @since 6.8
 */
@Incubating
public enum RepositoriesMode {
    /**
     * If this mode is set, any repository declared on a project will cause
     * the project to use the repositories declared by the project, ignoring
     * those declared in settings.
     *
     * This is the default behavior.
     */
    PREFER_PROJECT,

    /**
     * If this mode is set, any repository declared directly in a project,
     * either directly or via a plugin, will be ignored.
     */
    PREFER_SETTINGS,

    /**
     * If this mode is set, any repository declared directly in a project,
     * either directly or via a plugin, will trigger a build error.
     */
    FAIL_ON_PROJECT_REPOS;
}
