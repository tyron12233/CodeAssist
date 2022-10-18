package org.gradle.configuration;

import org.gradle.api.internal.SettingsInternal;
import org.gradle.groovy.scripts.BasicScript;
import org.gradle.initialization.SettingsScript;

public class SettingScriptTarget extends DefaultScriptTarget {
    public SettingScriptTarget(SettingsInternal target) {
        super(target);
    }

    @Override
    public String getId() {
        return "settings";
    }

    @Override
    public Class<? extends BasicScript> getScriptClass() {
        return SettingsScript.class;
    }

    @Override
    public boolean getSupportsPluginsBlock() {
        return true;
    }
}
