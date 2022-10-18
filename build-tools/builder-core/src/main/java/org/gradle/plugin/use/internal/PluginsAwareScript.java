package org.gradle.plugin.use.internal;

import org.gradle.groovy.scripts.DefaultScript;
import org.gradle.plugin.management.internal.PluginRequests;
import org.gradle.plugin.use.PluginDependenciesSpec;
import org.gradle.util.ConfigureUtil;

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
