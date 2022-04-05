package com.tyron.builder.api.internal.fingerprint.impl;

import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.hashing.ConfigurableNormalizer;

public abstract class AbstractDirectorySensitiveFingerprintingStrategy extends AbstractFingerprintingStrategy {
    private final DirectorySensitivity directorySensitivity;

    public AbstractDirectorySensitiveFingerprintingStrategy(String identifier, DirectorySensitivity directorySensitivity, ConfigurableNormalizer contentNormalizer) {
        super(identifier, hasher -> {
            contentNormalizer.appendConfigurationToHasher(hasher);
            hasher.putInt(directorySensitivity.ordinal());
        });
        this.directorySensitivity = directorySensitivity;
    }

    protected DirectorySensitivity getDirectorySensitivity() {
        return directorySensitivity;
    }
}
