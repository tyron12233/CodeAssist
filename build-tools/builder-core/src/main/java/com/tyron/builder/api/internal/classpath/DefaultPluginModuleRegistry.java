package com.tyron.builder.api.internal.classpath;

import com.tyron.builder.util.GUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

public class DefaultPluginModuleRegistry implements PluginModuleRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPluginModuleRegistry.class);
    private final ModuleRegistry moduleRegistry;

    public DefaultPluginModuleRegistry(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public Set<Module> getApiModules() {
        Properties properties = loadPluginProperties("/gradle-plugins.properties");
        return loadModules(properties);
    }

    @Override
    public Set<Module> getImplementationModules() {
        Properties properties = loadPluginProperties("/gradle-implementation-plugins.properties");
        return loadModules(properties);
    }

    private Set<Module> loadModules(Properties properties) {
        Set<Module> modules = new LinkedHashSet<Module>();
        String plugins = properties.getProperty("plugins", "");
        for (String pluginModule : plugins.split(",")) {
            try {
                modules.add(moduleRegistry.getModule(pluginModule));
            } catch (UnknownModuleException e) {
                // Ignore
                LOGGER.debug("Cannot find module for plugin {}. Ignoring.", pluginModule);
            }
        }
        return modules;
    }

    private Properties loadPluginProperties(String resource) {
        URL url = getClass().getResource(resource);
        if (url == null) {
            return new Properties();
        }
        return GUtil.loadProperties(url);
    }
}
