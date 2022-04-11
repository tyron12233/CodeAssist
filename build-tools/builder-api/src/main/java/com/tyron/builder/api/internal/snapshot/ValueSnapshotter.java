package com.tyron.builder.api.internal.snapshot;

import org.jetbrains.annotations.Nullable;

public interface ValueSnapshotter {
    /**
     * Creates a {@link ValueSnapshot} of the given value, that contains a snapshot of the current state of the value. A snapshot represents an immutable fingerprint of the value that can be later used to determine if a value has changed.
     *
     * <p>The snapshots must contain no references to the ClassLoader of the value.</p>
     *
     * @throws ValueSnapshottingException On failure to snapshot the value.
     */
    ValueSnapshot snapshot(@Nullable Object value) throws ValueSnapshottingException;

    /**
     * Creates a snapshot of the given value, given a candidate snapshot. If the value is the same as the value provided by the candidate snapshot, the candidate <em>must</em> be returned.
     *
     * @throws ValueSnapshottingException On failure to snapshot the value.
     */
    ValueSnapshot snapshot(@Nullable Object value, ValueSnapshot candidate) throws ValueSnapshottingException;
}
