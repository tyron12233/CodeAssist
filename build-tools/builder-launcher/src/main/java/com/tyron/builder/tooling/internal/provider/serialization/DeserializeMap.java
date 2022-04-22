package com.tyron.builder.tooling.internal.provider.serialization;

public interface DeserializeMap {
    /**
     * Loads a serialized Class.
     */
    Class<?> resolveClass(ClassLoaderDetails classLoaderDetails, String className) throws ClassNotFoundException;
}
