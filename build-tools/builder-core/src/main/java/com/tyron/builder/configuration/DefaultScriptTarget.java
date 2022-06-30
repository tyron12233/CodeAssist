package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.groovy.scripts.BasicScript;
import com.tyron.builder.groovy.scripts.DefaultScript;

import groovy.lang.Script;

public class DefaultScriptTarget implements ScriptTarget {
    private final Object target;

    public DefaultScriptTarget(Object target) {
        this.target = target;
    }

    @Override
    public String getId() {
        return "dsl";
    }

    @Override
    public PluginManagerInternal getPluginManager() {
        return target instanceof PluginAwareInternal ? ((PluginAwareInternal) target).getPluginManager() : null;
    }

    @Override
    public Class<? extends BasicScript> getScriptClass() {
        return DefaultScript.class;
    }

    @Override
    public String getClasspathBlockName() {
        return "buildscript";
    }

    @Override
    public boolean getSupportsPluginsBlock() {
        return false;
    }

    @Override
    public boolean getSupportsPluginManagementBlock() {
        return false;
    }

    @Override
    public boolean getSupportsMethodInheritance() {
        return false;
    }

    @Override
    public void attachScript(Script script) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addConfiguration(Runnable runnable, boolean deferrable) {
        runnable.run();
    }
}
