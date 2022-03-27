package com.tyron.builder.api.internal.state;

import org.jetbrains.annotations.Nullable;

/**
 * Implemented by types whose state is fully managed by Gradle. Mixed into generated classes whose state is fully managed.
 */
public interface Managed {
    /**
     * Returns a snapshot of the current state of this object. This can be passed to the {@link ManagedFactory#fromState(Class, Object)} method to recreate this object from the snapshot.
     * Note that the state may not be immutable, so should be made isolated to reuse in another context. The state can also be fingerprinted to generate a fingerprint of this object.
     *
     * <p><em>Note that currently the state should reference only JVM and core Gradle types when {@link #isImmutable()} returns true.</em></p>
     */
    @Nullable
    Object unpackState();

    /**
     * Is this object graph immutable? Returns false if this object <em>may</em> be mutable, in which case the state should be unpacked and isolated.
     */
    boolean isImmutable();

    /**
     * Returns the public type of this managed instance. Currently is used to identify the implementation.
     */
    Class<?> publicType();

    /**
     * Returns the id of a factory that can be used to create new instances of this type.
     */
    int getFactoryId();
}