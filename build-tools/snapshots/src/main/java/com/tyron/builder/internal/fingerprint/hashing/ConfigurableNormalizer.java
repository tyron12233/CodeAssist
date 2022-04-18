package com.tyron.builder.internal.fingerprint.hashing;

import com.google.common.hash.Hasher;

/**
 * A resource normalizer which is configurable.
 *
 * Allows tracking changes to its configuration.
 */
public interface ConfigurableNormalizer {
    void appendConfigurationToHasher(Hasher hasher);
}