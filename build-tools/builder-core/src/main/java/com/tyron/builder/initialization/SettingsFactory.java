package com.tyron.builder.initialization;

import java.io.File;
import java.util.Map;

import static java.util.Collections.emptyMap;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.DynamicObjectAware;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.extensibility.ExtensibleDynamicObject;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.scopes.ServiceRegistryFactory;

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
