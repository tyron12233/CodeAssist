package com.tyron.builder.plugin.use;

import org.jetbrains.annotations.Nullable;

public interface PluginId {

    /**
     * The fully qualified plugin ID.
     */
    String getId();

    /**
     * The namespace of the plugin or {@code null} if the ID contains no {@code .}.
     */
    @Nullable
    String getNamespace();

    /**
     * The plugin name without the namespace.
     */
    String getName();

    /**
     * Takes this unqualified plugin ID and adds a namespace.
     *
     * @param namespace the namespace to add.
     * @return the plugin ID qualified with the given namespace
     * @throws IllegalArgumentException if the ID already had a namespace
     */
    PluginId withNamespace(String namespace);
}