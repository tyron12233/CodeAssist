package com.tyron.builder.tooling.internal.provider.serialization;

import javax.annotation.concurrent.ThreadSafe;

/**
 * <p>Implementations must allow concurrent sessions.
 */
@ThreadSafe
public interface PayloadClassLoaderRegistry {
    /**
     * Starts serializing an object graph.
     * The returned value is not required to be thread-safe.
     */
    SerializeMap newSerializeSession();

    /**
     * Starts deserializing an object graph.
     * The returned value is not required to be thread-safe.
     */
    DeserializeMap newDeserializeSession();
}
