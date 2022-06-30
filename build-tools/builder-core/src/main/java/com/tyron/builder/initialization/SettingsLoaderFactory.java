package com.tyron.builder.initialization;

public interface SettingsLoaderFactory {
    /**
     * Create a SettingsLoader for a top-level build.
     */
    SettingsLoader forTopLevelBuild();

    /**
     * Create a SettingsLoader for a nested build.
     */
    SettingsLoader forNestedBuild();
}

