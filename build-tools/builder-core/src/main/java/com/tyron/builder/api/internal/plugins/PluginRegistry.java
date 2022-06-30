package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.plugin.use.PluginId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

@ThreadSafe
public interface PluginRegistry {
    <T> PluginImplementation<T> inspect(Class<T> clazz);

    /**
     * Extracts plugin information for the given class, if known to this registry.
     */
    @Nullable
    <T> PluginImplementation<T> maybeInspect(Class<T> clazz);

    /**
     * Locates the plugin with the given id. Note that the id of the result may be different to the requested id.
     */
    @Nullable
    PluginImplementation<?> lookup(PluginId pluginId);

    PluginRegistry createChild(ClassLoaderScope lookupScope);

    /**
     * Finds the plugin id which corresponds to the supplied class name.
     * @param clazz the class to look for
     * @return the plugin id for this class.
     */
    Optional<PluginId> findPluginForClass(Class<?> clazz);

}
