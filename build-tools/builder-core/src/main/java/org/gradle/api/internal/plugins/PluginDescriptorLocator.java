package org.gradle.api.internal.plugins;

public interface PluginDescriptorLocator {

    PluginDescriptor findPluginDescriptor(String pluginId);

}
