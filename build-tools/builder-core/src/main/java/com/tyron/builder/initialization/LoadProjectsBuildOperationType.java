package com.tyron.builder.initialization;

import com.tyron.builder.internal.operations.BuildOperationType;

import java.util.Set;

/**
 * An operation to load the project structure from the processed settings.
 * Provides details of the project structure without projects being configured.
 *
 * @since 4.2
 */
public final class LoadProjectsBuildOperationType implements BuildOperationType<LoadProjectsBuildOperationType.Details, LoadProjectsBuildOperationType.Result> {

    public interface Details {
        /**
         * @since 4.6
         */
        String getBuildPath();
    }

    public interface Result {
        /**
         * The path of the build configuration that contains these projects.
         * This will be ':' for top-level builds. Nested builds will have a sub-path.
         *
         * @see org.gradle.api.internal.GradleInternal#getIdentityPath()
         */
        String getBuildPath();

        /**
         * A description of the root Project for this build.
         *
         * @see org.gradle.api.initialization.Settings#getRootProject()
         */
        Project getRootProject();

        interface Project {

            /**
             * The name of the project.
             *
             * @see org.gradle.api.Project#getName()
             */
            String getName();

            /**
             * The path of the project.
             *
             * @see org.gradle.api.Project#getPath()
             */
            String getPath();

            /**
             * The path of the project within the entire build execution.
             * For top-level builds this will be the same as {@link #getPath()}.
             * For nested builds the project path will be prefixed with a build path.
             *
             * @see org.gradle.api.internal.project.ProjectInternal#getIdentityPath()
             */
            String getIdentityPath();

            /**
             * The absolute file path of the project directory.
             *
             * @see org.gradle.api.Project#getProjectDir()
             */
            String getProjectDir();

            /**
             * The absolute file path of the projects build file.
             *
             * @see org.gradle.api.Project#getBuildFile()
             */
            String getBuildFile();

            /**
             * The child projects of this project.
             * No null values.
             * Ordered by project name lexicographically.
             *
             * @see org.gradle.api.Project#getChildProjects()
             */

            Set<Project> getChildren();
        }
    }


}
