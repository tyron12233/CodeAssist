package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.Hasher;
import org.gradle.internal.fingerprint.hashing.ConfigurableNormalizer;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public interface ResourceFilter extends ConfigurableNormalizer {
    ResourceFilter FILTER_NOTHING = new ResourceFilter() {
        @Override
        public boolean shouldBeIgnored(Supplier<String[]> relativePathFactory) {
            return false;
        }

        @Override
        public void appendConfigurationToHasher(Hasher hasher) {
            hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        }
    };

    boolean shouldBeIgnored(Supplier<String[]> relativePathFactory);
}