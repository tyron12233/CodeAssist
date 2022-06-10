package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.internal.composite.CompositeBuildSettingsLoader;

public class InitScriptHandlingSettingsLoader implements SettingsLoader {
    private final SettingsLoader delegate;
    private final InitScriptHandler initScriptHandler;

    public InitScriptHandlingSettingsLoader(SettingsLoader delegate, InitScriptHandler initScriptHandler) {
        this.delegate = delegate;
        this.initScriptHandler = initScriptHandler;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        initScriptHandler.executeScripts(gradle);
        return delegate.findAndLoadSettings(gradle);
    }
}