package org.gradle.initialization.buildsrc;

import org.gradle.internal.operations.BuildOperationType;

/**
 * Building the buildSrc project.
 *
 * @since 4.3
 */
public final class BuildBuildSrcBuildOperationType implements BuildOperationType<BuildBuildSrcBuildOperationType.Details, BuildBuildSrcBuildOperationType.Result> {

    public interface Details {
        /**
         * Returns the path of the _containing_ build.
         * @since 4.6
         */
        String getBuildPath();
    }

    public interface Result {
    }

    private BuildBuildSrcBuildOperationType(){
    }
}