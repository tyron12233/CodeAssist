package com.tyron.builder.plugin.use.internal;

import com.tyron.builder.groovy.scripts.DefaultScript;
import com.tyron.builder.plugin.management.internal.PluginRequests;
import com.tyron.builder.plugin.use.PluginDependenciesSpec;
import com.tyron.builder.util.ConfigureUtil;

import groovy.lang.Closure;

abstract public class PluginsAwareScript extends DefaultScript {

    private PluginRequestCollector pluginRequestCollector;

    public void plugins(int lineNumber, Closure configureClosure) {
        if (pluginRequestCollector == null) {
            pluginRequestCollector = new PluginRequestCollector(getScriptSource());
        }
        PluginDependenciesSpec spec = pluginRequestCollector.createSpec(lineNumber);
        ConfigureUtil.configure(configureClosure, spec);
    }

    public PluginRequests getPluginRequests() {
        if (pluginRequestCollector != null) {
            return pluginRequestCollector.getPluginRequests();
        }
        return PluginRequests.EMPTY;
    }

}
