package org.gradle.api.internal.project;

import org.gradle.api.Action;
import org.gradle.api.internal.plugins.DefaultObjectConfigurationAction;
import org.gradle.api.internal.plugins.PluginAwareInternal;
import org.gradle.api.plugins.ObjectConfigurationAction;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.configuration.ConfigurationTargetIdentifier;
import org.gradle.util.ConfigureUtil;

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
