package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.internal.composite.CompositeBuildSettingsLoader;

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