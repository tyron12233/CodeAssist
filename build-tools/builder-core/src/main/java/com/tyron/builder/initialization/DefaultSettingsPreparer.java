package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;

public class DefaultSettingsPreparer implements SettingsPreparer {
    private final SettingsLoaderFactory settingsLoaderFactory;

    public DefaultSettingsPreparer(SettingsLoaderFactory settingsLoaderFactory) {
        this.settingsLoaderFactory = settingsLoaderFactory;
    }

    @Override
    public void prepareSettings(GradleInternal gradle) {
        SettingsLoader settingsLoader = gradle.isRootBuild() ? settingsLoaderFactory.forTopLevelBuild() : settingsLoaderFactory.forNestedBuild();
        settingsLoader.findAndLoadSettings(gradle);
    }
}

