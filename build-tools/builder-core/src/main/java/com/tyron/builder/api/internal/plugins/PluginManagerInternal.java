package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.plugins.AppliedPlugin;
import com.tyron.builder.api.plugins.PluginContainer;
import com.tyron.builder.api.plugins.PluginManager;
import com.tyron.builder.plugin.use.PluginId;

import java.util.Optional;

public interface PluginManagerInternal extends PluginManager {
    void apply(PluginImplementation<?> plugin);

    <P extends Plugin> P addImperativePlugin(PluginImplementation<P> plugin);

    <P extends Plugin> P addImperativePlugin(Class<P> plugin);

    PluginContainer getPluginContainer();

    <P extends Plugin<?>> Optional<PluginId> findPluginIdForClass(Class<P> plugin);

    DomainObjectSet<PluginWithId> pluginsForId(String id);

    class PluginWithId {
        final PluginId id;
        final Class<?> clazz;

        public PluginWithId(PluginId id, Class<?> clazz) {
            this.id = id;
            this.clazz = clazz;
        }

        AppliedPlugin asAppliedPlugin() {
            return new DefaultAppliedPlugin(id);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PluginWithId that = (PluginWithId) o;

            return clazz.equals(that.clazz) && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + clazz.hashCode();
            return result;
        }
    }
}
