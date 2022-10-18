package org.gradle.initialization;

import org.gradle.internal.operations.BuildOperationType;

public class ConfigureBuildBuildOperationType implements BuildOperationType<ConfigureBuildBuildOperationType.Details, ConfigureBuildBuildOperationType.Result> {
//    @UsedByScanPlugin
    public interface Details {
        /**
         * @since 4.6
         */
        String getBuildPath();
    }

    public interface Result {
    }

    private ConfigureBuildBuildOperationType(){
    }
}