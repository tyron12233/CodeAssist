package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.plugins.AppliedPlugin;
import com.tyron.builder.plugin.use.PluginId;

class DefaultAppliedPlugin implements AppliedPlugin {

    private final PluginId pluginId;

    public DefaultAppliedPlugin(PluginId pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public String getId() {
        return pluginId.toString();
    }

    @Override
    public String getNamespace() {
        return pluginId.getNamespace();
    }

    @Override
    public String getName() {
        return pluginId.getName();
    }

}
