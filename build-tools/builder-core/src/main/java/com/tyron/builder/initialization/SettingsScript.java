package com.tyron.builder.initialization;

import com.tyron.builder.plugin.use.internal.PluginsAwareScript;

public abstract class SettingsScript extends PluginsAwareScript {
    public String toString() {
        return getScriptTarget().toString();
    }
}
