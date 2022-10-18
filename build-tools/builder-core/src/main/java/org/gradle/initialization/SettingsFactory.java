package org.gradle.initialization;

import java.io.File;
import java.util.Map;

import static java.util.Collections.emptyMap;

import org.gradle.StartParameter;
import org.gradle.api.internal.DynamicObjectAware;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.initialization.ScriptHandlerFactory;
import org.gradle.api.internal.properties.GradleProperties;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.extensibility.ExtensibleDynamicObject;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.scopes.ServiceRegistryFactory;

public class SettingsFactory {
    private final Instantiator instantiator;
    private final ServiceRegistryFactory serviceRegistryFactory;
    private final ScriptHandlerFactory scriptHandlerFactory;

    public SettingsFactory(Instantiator instantiator, ServiceRegistryFactory serviceRegistryFactory, ScriptHandlerFactory scriptHandlerFactory) {
        this.instantiator = instantiator;
        this.serviceRegistryFactory = serviceRegistryFactory;
        this.scriptHandlerFactory = scriptHandlerFactory;
    }

    public SettingsInternal createSettings(
        GradleInternal gradle,
        File settingsDir,
        ScriptSource settingsScript,
        GradleProperties gradleProperties,
        StartParameter startParameter,
        ClassLoaderScope baseClassLoaderScope
    ) {
        ClassLoaderScope classLoaderScope = baseClassLoaderScope.createChild("settings[" + gradle.getIdentityPath() + "]");
        DefaultSettings settings = instantiator.newInstance(
            DefaultSettings.class,
            serviceRegistryFactory,
            gradle,
            classLoaderScope,
            baseClassLoaderScope,
            scriptHandlerFactory.create(settingsScript, classLoaderScope),
            settingsDir,
            settingsScript,
            startParameter
        );
        Map<String, Object> properties = gradleProperties.mergeProperties(emptyMap());
        DynamicObject dynamicObject = ((DynamicObjectAware) settings).getAsDynamicObject();
        ((ExtensibleDynamicObject) dynamicObject).addProperties(properties);
        return settings;
    }
}
