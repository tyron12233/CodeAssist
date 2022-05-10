package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.configuration.internal.ExecuteListenerBuildOperationType;
import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

/**
 * Details about a plugin being applied.
 *
 * @since 4.0
 */
public final class ApplyPluginBuildOperationType implements BuildOperationType<ApplyPluginBuildOperationType.Details, ApplyPluginBuildOperationType.Result> {

    public interface Details {

        /**
         * The fully qualified plugin ID, if known.
         */
        @Nullable
        String getPluginId();

        /**
         * The class of the plugin implementation.
         */
        Class<?> getPluginClass();

        /**
         * The target of the plugin.
         * One of "gradle", "settings", "project".
         */
        String getTargetType();

        /**
         * If the target is a project, its path.
         */
        @Nullable
        String getTargetPath();

        /**
         * The build path of the target.
         */
        String getBuildPath();

        /**
         * A unique ID for this plugin application, within this build operation tree.
         *
         * @see ExecuteListenerBuildOperationType.Details#getApplicationId()
         * @since 4.10
         */
        long getApplicationId();

    }

    public interface Result {
    }


    private ApplyPluginBuildOperationType() {
    }
}
