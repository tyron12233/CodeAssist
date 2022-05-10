package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.plugins.DefaultObjectConfigurationAction;
import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.api.plugins.ObjectConfigurationAction;
import com.tyron.builder.api.plugins.PluginContainer;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;
import com.tyron.builder.util.ConfigureUtil;

import javax.inject.Inject;
import java.util.Map;

import groovy.lang.Closure;

public abstract class AbstractPluginAware implements PluginAwareInternal {

    @Override
    public void apply(Closure closure) {
        apply(ConfigureUtil.configureUsing(closure));
    }

    @Override
    public void apply(Action<? super ObjectConfigurationAction> action) {
        DefaultObjectConfigurationAction configAction = createObjectConfigurationAction();
        action.execute(configAction);
        configAction.execute();
    }

    @Override
    public void apply(Map<String, ?> options) {
        DefaultObjectConfigurationAction action = createObjectConfigurationAction();
        ConfigureUtil.configureByMap(options, action);
        action.execute();
    }

    @Override
    public PluginContainer getPlugins() {
        return getPluginManager().getPluginContainer();
    }

    @Override
    @Inject
    public ConfigurationTargetIdentifier getConfigurationTargetIdentifier() {
        throw new UnsupportedOperationException();
    }

    abstract protected DefaultObjectConfigurationAction createObjectConfigurationAction();

}
