package com.tyron.builder.api.internal.plugins;

import java.net.URL;

public class ClassloaderBackedPluginDescriptorLocator implements PluginDescriptorLocator {

    private final ClassLoader classLoader;

    public ClassloaderBackedPluginDescriptorLocator(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public PluginDescriptor findPluginDescriptor(String pluginId) {
        URL resource = classLoader.getResource("META-INF/gradle-plugins/" + pluginId + ".properties");
        if (resource == null) {
            return null;
        } else {
            return new PluginDescriptor(resource);
        }
    }

}
