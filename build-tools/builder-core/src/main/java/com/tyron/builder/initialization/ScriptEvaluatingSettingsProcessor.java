package com.tyron.builder.initialization;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.configuration.ScriptPlugin;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.groovy.scripts.TextResourceScriptSource;
import com.tyron.builder.internal.resource.TextFileResourceLoader;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ScriptEvaluatingSettingsProcessor implements SettingsProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptEvaluatingSettingsProcessor.class);

    private final SettingsFactory settingsFactory;
    private final GradleProperties gradleProperties;
    private final ScriptPluginFactory configurerFactory;
    private final TextFileResourceLoader textFileResourceLoader;

    public ScriptEvaluatingSettingsProcessor(
        ScriptPluginFactory configurerFactory,
        SettingsFactory settingsFactory,
        GradleProperties gradleProperties,
        TextFileResourceLoader textFileResourceLoader
    ) {
        this.configurerFactory = configurerFactory;
        this.settingsFactory = settingsFactory;
        this.gradleProperties = gradleProperties;
        this.textFileResourceLoader = textFileResourceLoader;
    }

    @Override
    public SettingsInternal process(
        GradleInternal gradle,
        SettingsLocation settingsLocation,
        ClassLoaderScope baseClassLoaderScope,
        StartParameter startParameter
    ) {
        Timer settingsProcessingClock = Time.startTimer();
        TextResourceScriptSource settingsScript = new TextResourceScriptSource(textFileResourceLoader.loadFile("settings file", settingsLocation.getSettingsFile()));
        SettingsInternal settings = settingsFactory.createSettings(gradle, settingsLocation.getSettingsDir(), settingsScript, gradleProperties, startParameter, baseClassLoaderScope);

        gradle.getBuildListenerBroadcaster().beforeSettings(settings);
        applySettingsScript(settingsScript, settings);
        LOGGER.debug("Timing: Processing settings took: {}", settingsProcessingClock.getElapsed());
        return settings;
    }

    private void applySettingsScript(TextResourceScriptSource settingsScript, final SettingsInternal settings) {
        ScriptPlugin configurer = configurerFactory.create(settingsScript, settings.getBuildscript(), settings.getClassLoaderScope(), settings.getBaseClassLoaderScope(), true);
        configurer.apply(settings);
    }
}
