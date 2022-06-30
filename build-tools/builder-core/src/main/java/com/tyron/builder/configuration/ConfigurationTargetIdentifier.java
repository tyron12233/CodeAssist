package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;

import javax.annotation.Nullable;

/**
 * Uniquely identifies the target of some configuration.
 *
 * This is primarily used to support
 * {@code ApplyScriptPluginBuildOperationType.Details} and {@code ApplyPluginBuildOperationType.Details}.
 */
public abstract class ConfigurationTargetIdentifier {

    private ConfigurationTargetIdentifier() {
    }

    public enum Type {
        GRADLE,
        SETTINGS,
        PROJECT;

        public final String label = name().toLowerCase();
    }

    public abstract Type getTargetType();

    /**
     * If type == project, that project's path (not identity path).
     * Else, null.
     */
    @Nullable
    public abstract String getTargetPath();

    public abstract String getBuildPath();

    /**
     * Returns null if the thing is of an unknown type.
     * This can happen with {@code apply(from: "foo", to: someTask)},
     * where “to” can be absolutely anything.
     */
    @Nullable
    public static ConfigurationTargetIdentifier of(Object any) {
        if (any instanceof PluginAwareInternal) {
            return ((PluginAwareInternal) any).getConfigurationTargetIdentifier();
        } else {
            return null;
        }
    }

    public static ConfigurationTargetIdentifier of(final ProjectInternal project) {
        return new ConfigurationTargetIdentifier() {
            @Override
            public Type getTargetType() {
                return Type.PROJECT;
            }

            @Nullable
            @Override
            public String getTargetPath() {
                return project.getProjectPath().getPath();
            }

            @Override
            public String getBuildPath() {
                return project.getGradle().getIdentityPath().getPath();
            }
        };
    }

    public static ConfigurationTargetIdentifier of(final SettingsInternal settings) {
        return new ConfigurationTargetIdentifier() {
            @Override
            public Type getTargetType() {
                return Type.SETTINGS;
            }

            @Nullable
            @Override
            public String getTargetPath() {
                return null;
            }

            @Override
            public String getBuildPath() {
                return settings.getGradle().getIdentityPath().getPath();
            }
        };
    }

    public static ConfigurationTargetIdentifier of(final GradleInternal gradle) {
        return new ConfigurationTargetIdentifier() {
            @Override
            public Type getTargetType() {
                return Type.GRADLE;
            }

            @Nullable
            @Override
            public String getTargetPath() {
                return null;
            }

            @Override
            public String getBuildPath() {
                return gradle.getIdentityPath().getPath();
            }
        };
    }

}