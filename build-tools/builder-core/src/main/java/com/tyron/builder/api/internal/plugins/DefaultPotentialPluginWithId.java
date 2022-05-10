package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.plugin.use.PluginId;

public class DefaultPotentialPluginWithId<T> implements PluginImplementation<T> {

    private final PluginId pluginId;
    private final PotentialPlugin<? extends T> potentialPlugin;

    public static <T> DefaultPotentialPluginWithId<T> of(PluginId pluginId, PotentialPlugin<T> potentialPlugin) {
        return new DefaultPotentialPluginWithId<T>(pluginId, potentialPlugin);
    }

    protected DefaultPotentialPluginWithId(PluginId pluginId, PotentialPlugin<? extends T> potentialPlugin) {
        this.pluginId = pluginId;
        this.potentialPlugin = potentialPlugin;
    }

    @Override
    public DisplayName getDisplayName() {
        if (pluginId == null) {
            return Describables.quoted("plugin class", asClass().getName());
        }
        return Describables.quoted("plugin", pluginId);
    }

    @Override
    public PluginId getPluginId() {
        return pluginId;
    }

    @Override
    public Class<? extends T> asClass() {
        return potentialPlugin.asClass();
    }

    @Override
    public boolean isImperative() {
        return potentialPlugin.isImperative();
    }

    @Override
    public boolean isHasRules() {
        return potentialPlugin.isHasRules();
    }

    @Override
    public Type getType() {
        return potentialPlugin.getType();
    }

    @Override
    public boolean isAlsoKnownAs(PluginId id) {
        return false;
    }
}
