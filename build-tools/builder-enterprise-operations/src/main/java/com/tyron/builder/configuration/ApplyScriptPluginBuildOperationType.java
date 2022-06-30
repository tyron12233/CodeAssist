package com.tyron.builder.configuration;

import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

/**
 * Details about a script plugin being applied.
 *
 * @since 4.0
 */
public final class ApplyScriptPluginBuildOperationType implements BuildOperationType<ApplyScriptPluginBuildOperationType.Details, ApplyScriptPluginBuildOperationType.Result> {

    public interface Details {

        /**
         * The absolute path to the script file.
         * Null if was not a script file.
         * Null if uri != null.
         */
        @Nullable
        String getFile();

        /**
         * The URI of the script.
         * Null if was not applied as URI.
         * Null if file != null.
         */
        @Nullable
        String getUri();

        /**
         * The target of the script.
         * One of "gradle", "settings", "project" or null.
         * If null, the target is an arbitrary object.
         */
        @Nullable
        String getTargetType();

        /**
         * If the target is a project, its path.
         */
        @Nullable
        String getTargetPath();

        /**
         * The build path, if the target is a known type (i.e. targetType != null)
         */
        @Nullable
        String getBuildPath();

        /**
         * A unique ID for this plugin application, within this build operation tree.
         *
         * @see org.gradle.configuration.internal.ExecuteListenerBuildOperationType.Details#getApplicationId()
         * @since 4.10
         */
        long getApplicationId();

    }

    public interface Result {
    }

    private ApplyScriptPluginBuildOperationType() {
    }
}
