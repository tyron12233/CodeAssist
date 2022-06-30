package com.tyron.builder.initialization.layout;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.StartParameterInternal;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Configuration which affects the (static) layout of a build.
 */
public class BuildLayoutConfiguration {
    private final File currentDir;
    private final boolean searchUpwards;
    private final File settingsFile;
    private final boolean useEmptySettings;

    public BuildLayoutConfiguration(StartParameter startParameter) {
        currentDir = startParameter.getCurrentDir();
        searchUpwards = ((StartParameterInternal)startParameter).isSearchUpwards();
        @SuppressWarnings("deprecation")
        File customSettingsFile = startParameter.getSettingsFile();
        this.settingsFile = customSettingsFile;
        useEmptySettings = ((StartParameterInternal)startParameter).isUseEmptySettings();
    }

    public File getCurrentDir() {
        return currentDir;
    }

    public boolean isSearchUpwards() {
        return searchUpwards;
    }

    /**
     * When null, use the default. When not null, use the given value.
     */
    @Nullable
    public File getSettingsFile() {
        return settingsFile;
    }

    public boolean isUseEmptySettings() {
        return useEmptySettings;
    }
}