package com.tyron.builder.api.internal.hash;

import com.google.common.hash.Hasher;

/**
 * A snapshot of the state of some thing.
 */
public interface Hashable {
    /**
     * Appends the snapshot to the given hasher.
     */
    void appendToHasher(Hasher hasher);
}