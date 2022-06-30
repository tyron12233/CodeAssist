package com.tyron.builder.internal.isolation;

import com.tyron.builder.internal.hash.Hashable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import org.jetbrains.annotations.Nullable;

/**
 * Isolatable objects can return an isolated instance of the given type T from which this object was created.
 * An <b>isolated</b> instance has the same internal state as the original object on which this isolatable was based,
 * but it is guaranteed not to retain any references to mutable state from the original instance.
 * <p>
 * The primary reason to need such an isolated instance of an object is to ensure that work can be done in parallel using the instance without
 * fear that its internal state is changing while the work is being carried out.
 */
public interface Isolatable<T> extends Hashable {
    /**
     * Returns this value as a {@link ValueSnapshot}. The returned value should not hold any references to user ClassLoaders.
     */
    ValueSnapshot asSnapshot();

    /**
     * Returns an instance of T that is isolated from the original object and all other instances.
     * When T is mutable, a new instance is created on each call. When T is immutable, a new instance may or may not be created on each call. This may potentially be expensive.
     */
    @Nullable
    T isolate();

    /**
     * Returns an instance of S constructed from the state of the original object, if possible.
     *
     * @return null if not supported, or the value is null.
     */
    @Nullable
    <S> S coerce(Class<S> type);
}
