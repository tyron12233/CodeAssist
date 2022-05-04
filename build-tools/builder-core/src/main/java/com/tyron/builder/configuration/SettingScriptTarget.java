package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.groovy.scripts.BasicScript;
import com.tyron.builder.initialization.SettingsScript;

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
