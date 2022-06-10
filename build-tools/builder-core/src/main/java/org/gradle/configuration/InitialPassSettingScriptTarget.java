package org.gradle.configuration;

import org.gradle.api.internal.SettingsInternal;

public class InitialPassSettingScriptTarget extends SettingScriptTarget {
    public InitialPassSettingScriptTarget(SettingsInternal target) {
        super(target);
    }

    @Override
    public boolean getSupportsPluginManagementBlock() {
        return true;
    }

    @Override
    public boolean getSupportsPluginsBlock() {
        return true;
    }
}
