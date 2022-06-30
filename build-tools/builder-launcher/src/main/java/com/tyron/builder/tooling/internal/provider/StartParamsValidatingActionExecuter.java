package com.tyron.builder.tooling.internal.provider;

import com.tyron.builder.StartParameter;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildActionResult;

import java.io.File;

/**
 * Validates certain aspects of the start parameters, prior to starting a session using the parameters.
 */
public class StartParamsValidatingActionExecuter implements BuildActionExecuter<BuildActionParameters, BuildRequestContext> {
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate;

    public StartParamsValidatingActionExecuter(BuildActionExecuter<BuildActionParameters, BuildRequestContext> delegate) {
        this.delegate = delegate;
    }

    @Override
    public BuildActionResult execute(BuildAction action, BuildActionParameters actionParameters, BuildRequestContext requestContext) {
        StartParameter startParameter = action.getStartParameter();
        @SuppressWarnings("deprecation")
        File customBuildFile = startParameter.getBuildFile();
        if (customBuildFile != null) {
            validateIsFileAndExists(customBuildFile, "build file");
        }
        if (startParameter.getProjectDir() != null) {
            if (!startParameter.getProjectDir().isDirectory()) {
                if (!startParameter.getProjectDir().exists()) {
                    throw new IllegalArgumentException(String.format("The specified project directory '%s' does not exist.", startParameter.getProjectDir()));
                }
                throw new IllegalArgumentException(String.format("The specified project directory '%s' is not a directory.", startParameter.getProjectDir()));
            }
        }
        @SuppressWarnings("deprecation")
        File customSettingsFile = startParameter.getSettingsFile();
        if (customSettingsFile != null) {
            validateIsFileAndExists(customSettingsFile, "settings file");
        }
        for (File initScript : startParameter.getInitScripts()) {
            validateIsFileAndExists(initScript, "initialization script");
        }

        return delegate.execute(action, actionParameters, requestContext);
    }

    private static void validateIsFileAndExists(File file, String fileType) {
        if (!file.isFile()) {
            if (!file.exists()) {
                throw new IllegalArgumentException(String.format("The specified %s '%s' does not exist.", fileType, file));
            }
            throw new IllegalArgumentException(String.format("The specified %s '%s' is not a file.", fileType, file));
        }
    }
}