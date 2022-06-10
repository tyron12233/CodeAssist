package org.gradle.api.internal.plugins;

import org.gradle.api.DomainObjectSet;
import org.gradle.api.Plugin;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.PluginManager;
import org.gradle.plugin.use.PluginId;

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
