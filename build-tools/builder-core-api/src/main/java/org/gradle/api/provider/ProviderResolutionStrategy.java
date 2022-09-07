package org.gradle.api.provider;

import org.gradle.api.GradleException;
import org.jetbrains.annotations.Nullable;

public enum ProviderResolutionStrategy {
    ALLOW_ABSENT {
        @Override
        public <T> T resolve(Provider<T> provider) {
            return provider.getOrNull();
        }
    },
    REQUIRE_PRESENT {
        @Override
        public <T> T resolve(Provider<T> provider) {
            if (!provider.isPresent()) {
                throw new GradleException("Provider is empty: " + provider + " " + provider.getClass());
            }
            return provider.get();
        }
    };

    @Nullable
    public abstract <T> T resolve(Provider<T> provider);
}