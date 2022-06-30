package com.tyron.builder.api.internal.plugins;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.GeneratedSubclasses;
import com.tyron.builder.api.plugins.PluginCollection;
import com.tyron.builder.api.plugins.PluginContainer;
import com.tyron.builder.api.plugins.UnknownPluginException;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.plugin.use.internal.DefaultPluginId;

/**
 * This plugin collection is optimized based on the knowledge we have about how plugins
 * are applied. The plugin manager already keeps track of all plugins and ensures they
 * are only applied once. As a result, we don't need to keep another data structure here,
 * but can just share the one kept by the manager. This class forbids all mutations, as
 * manually adding/removing plugin instances does not make sense.
 */
public class DefaultPluginContainer extends DefaultPluginCollection<Plugin> implements PluginContainer {

    private final PluginRegistry pluginRegistry;
    private final PluginManagerInternal pluginManager;

    public DefaultPluginContainer(PluginRegistry pluginRegistry, final PluginManagerInternal pluginManager, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(Plugin.class, callbackActionDecorator);
        this.pluginRegistry = pluginRegistry;
        this.pluginManager = pluginManager;
    }

    void pluginAdded(Plugin plugin) {
        super.add(plugin);
    }

    @Override
    @Deprecated
    public boolean add(Plugin toAdd) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Plugin apply(String id) {
        PluginImplementation<?> plugin = pluginRegistry.lookup(DefaultPluginId.unvalidated(id));
        if (plugin == null) {
            throw new UnknownPluginException("Plugin with id '" + id + "' not found.");
        }

        if (!Plugin.class.isAssignableFrom(plugin.asClass())) {
            throw new IllegalArgumentException("Plugin implementation '" + plugin.asClass().getName() + "' does not implement the Plugin interface. This plugin cannot be applied directly via the PluginContainer.");
        } else {
            return pluginManager.addImperativePlugin(Cast.<PluginImplementation<Plugin<?>>>uncheckedNonnullCast(plugin));
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <P extends Plugin> P apply(Class<P> type) {
        return pluginManager.addImperativePlugin(type);
    }

    @Override
    public boolean hasPlugin(String id) {
        return findPlugin(id) != null;
    }

    @Override
    public boolean hasPlugin(Class<? extends Plugin> type) {
        return findPlugin(type) != null;
    }

    private Plugin<?> doFindPlugin(String id) {
        for (final PluginManagerInternal.PluginWithId pluginWithId : pluginManager.pluginsForId(id)) {
            Plugin<?> plugin = Iterables.tryFind(DefaultPluginContainer.this, new Predicate<Plugin>() {
                @Override
                public boolean apply(Plugin plugin) {
                    Class<?> pluginType = GeneratedSubclasses.unpackType(plugin);
                    return pluginWithId.clazz.equals(pluginType);
                }
            }).orNull();

            if (plugin != null) {
                return plugin;
            }
        }

        return null;
    }

    @Override
    public Plugin findPlugin(String id) {
        return doFindPlugin(id);
    }

    @Override
    public <P extends Plugin> P findPlugin(Class<P> type) {
        for (Plugin plugin : this) {
            Class<?> pluginType = GeneratedSubclasses.unpackType(plugin);
            if (pluginType.equals(type)) {
                return type.cast(plugin);
            }
        }
        return null;
    }

    @Override
    public Plugin getPlugin(String id) {
        Plugin<?> plugin = findPlugin(id);
        if (plugin == null) {
            throw new UnknownPluginException("Plugin with id " + id + " has not been used.");
        }
        return plugin;
    }

    @Override
    public Plugin getAt(String id) throws UnknownPluginException {
        return getPlugin(id);
    }

    @Override
    public <P extends Plugin> P getAt(Class<P> type) throws UnknownPluginException {
        return getPlugin(type);
    }

    @Override
    public <P extends Plugin> P getPlugin(Class<P> type) throws UnknownPluginException {
        P plugin = findPlugin(type);
        if (plugin == null) {
            throw new UnknownPluginException("Plugin with type " + type + " has not been used.");
        }
        return type.cast(plugin);
    }

    @Override
    public void withId(final String pluginId, final Action<? super Plugin> action) {
        Action<DefaultPluginManager.PluginWithId> wrappedAction = new Action<DefaultPluginManager.PluginWithId>() {
            @Override
            public void execute(final DefaultPluginManager.PluginWithId pluginWithId) {
                matching(plugin -> {
                    Class<?> pluginType = GeneratedSubclasses.unpackType(plugin);
                    return pluginWithId.clazz.equals(pluginType);
                }).all(action);
            }
        };

        pluginManager.pluginsForId(pluginId).all(wrappedAction);
    }

    @Override
    public <S extends Plugin> PluginCollection<S> withType(Class<S> type) {
        // runtime check because method is used from Groovy where type bounds are not respected
        if (!Plugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(String.format("'%s' does not implement the Plugin interface.", type.getName()));
        }

        return super.withType(type);
    }
}
