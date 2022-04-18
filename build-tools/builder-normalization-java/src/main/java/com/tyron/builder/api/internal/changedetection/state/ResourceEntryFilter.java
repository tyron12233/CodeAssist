package com.tyron.builder.api.internal.changedetection.state;


import com.google.common.hash.Hasher;
import com.tyron.builder.internal.fingerprint.hashing.ConfigurableNormalizer;

import java.nio.charset.StandardCharsets;

/**
 * A resource entry filter supporting exact matches of values.
 */
public interface ResourceEntryFilter extends ConfigurableNormalizer {
    ResourceEntryFilter FILTER_NOTHING = new ResourceEntryFilter() {
        @Override
        public boolean shouldBeIgnored(String entry) {
            return false;
        }

        @Override
        public void appendConfigurationToHasher(Hasher hasher) {
            hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        }
    };

    boolean shouldBeIgnored(String entry);
}