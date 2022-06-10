package org.gradle.internal.fingerprint.impl;

import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.hashing.ConfigurableNormalizer;

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
