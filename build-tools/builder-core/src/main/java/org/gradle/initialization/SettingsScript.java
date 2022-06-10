package org.gradle.initialization;

import org.gradle.plugin.use.internal.PluginsAwareScript;

public abstract class SettingsScript extends PluginsAwareScript {
    public String toString() {
        return getScriptTarget().toString();
    }
}
