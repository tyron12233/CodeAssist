package com.tyron.builder.api.providers;

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
            return provider.get();
        }
    };

    @Nullable
    public abstract <T> T resolve(Provider<T> provider);
}