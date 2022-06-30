package com.tyron.builder.tooling.internal.provider.serialization;

import java.util.Map;

public interface SerializeMap {
    /**
     * Visits a class to be serialized, returning the id of the deserialize ClassLoader to associate this class with.
     * The id is unique only for this serialization, and is used as the key for the map built by {@link #collectClassLoaderDefinitions(Map)}.
     *
     * @return The ClassLoader id.
     */
    short visitClass(Class<?> target);

    /**
     * Collects the set of ClassLoader definitions to use to deserialize the graph.
     *
     * @param details The map from ClassLoader id to details to use create that ClassLoader.
     */
    void collectClassLoaderDefinitions(Map<Short, ClassLoaderDetails> details);
}
