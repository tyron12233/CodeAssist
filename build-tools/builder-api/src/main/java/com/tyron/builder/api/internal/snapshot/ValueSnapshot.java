package com.tyron.builder.api.internal.snapshot;

import com.tyron.builder.api.internal.hash.Hashable;

import org.jetbrains.annotations.Nullable;

/**
 * An immutable snapshot of the state of some Java object or object graph.
 *
 * <p>Implementations are not required to be able to recreate the object, and should retain as little state as possible.
 * In particular, implementations should not hold on to user ClassLoaders.</p>
 */
public interface ValueSnapshot extends Hashable {
    /**
     * Takes a snapshot of the given value, using this as a candidate snapshot. If the value is the same as the value represented by this snapshot, this snapshot <em>must</em> be returned.
     */
    ValueSnapshot snapshot(@Nullable Object value, ValueSnapshotter snapshotter);
}