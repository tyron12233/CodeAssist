package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;

import javax.annotation.Nullable;

import static com.tyron.builder.internal.Cast.uncheckedCast;

public class ImperativeOnlyPluginTarget<T extends PluginAwareInternal> implements PluginTarget {

    private final T target;

    public ImperativeOnlyPluginTarget(T target) {
        this.target = target;
    }

    @Override
    public ConfigurationTargetIdentifier getConfigurationTargetIdentifier() {
        return target.getConfigurationTargetIdentifier();
    }

    @Override
    public void applyImperative(@Nullable String pluginId, Plugin<?> plugin) {
        // TODO validate that the plugin accepts this kind of argument
        Plugin<T> cast = uncheckedCast(plugin);
        cast.apply(target);
    }

    @Override
    public void applyRules(@Nullable String pluginId, Class<?> clazz) {
        String message = String.format("Cannot apply model rules of plugin '%s' as the target '%s' is not model rule aware", clazz.getName(), target.toString());
        throw new UnsupportedOperationException(message);
    }

    @Override
    public void applyImperativeRulesHybrid(@Nullable String pluginId, Plugin<?> plugin) {
        applyRules(pluginId, plugin.getClass());
    }

    @Override
    public String toString() {
        return target.toString();
    }
}
