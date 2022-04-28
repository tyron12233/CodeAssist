package com.tyron.builder.configuration.project;

import com.tyron.builder.internal.operations.BuildOperationType;

import java.io.File;

/**
 * Configuration of a project.
 *
 * @since 4.0
 */
public final class ConfigureProjectBuildOperationType implements BuildOperationType<ConfigureProjectBuildOperationType.Details, ConfigureProjectBuildOperationType.Result> {

    public interface Details {

        String getProjectPath();

        String getBuildPath();

        File getRootDir();

    }

    public interface Result {

    }

    final static Result RESULT = new Result() {
    };

    private ConfigureProjectBuildOperationType() {
    }

}
