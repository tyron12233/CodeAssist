package org.gradle.internal.fingerprint.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.fingerprint.hashing.ConfigurableNormalizer;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;


import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public abstract class AbstractFingerprintingStrategy implements FingerprintingStrategy {
    private final String identifier;
    private final CurrentFileCollectionFingerprint emptyFingerprint;
    private final HashCode configurationHash;

    public AbstractFingerprintingStrategy(
            String identifier,
            ConfigurableNormalizer configurableNormalizer
    ) {
        this.identifier = identifier;
        this.emptyFingerprint = new EmptyCurrentFileCollectionFingerprint(identifier);
        Hasher hasher = Hashing.md5().newHasher();
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        configurableNormalizer.appendConfigurationToHasher(hasher);
        this.configurationHash = hasher.hash();
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public CurrentFileCollectionFingerprint getEmptyFingerprint() {
        return emptyFingerprint;
    }

    @Nullable
    protected HashCode getNormalizedContentHash(FileSystemLocationSnapshot snapshot, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        try {
            return normalizedContentHasher.hash(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(failedToNormalize(snapshot), e);
        } catch (UncheckedIOException e) {
            throw new UncheckedIOException(failedToNormalize(snapshot), e.getCause());
        }
    }

    private static String failedToNormalize(FileSystemLocationSnapshot snapshot) {
        return String.format("Failed to normalize content of '%s'.", snapshot.getAbsolutePath());
    }

    @Override
    public HashCode getConfigurationHash() {
        return configurationHash;
    }
}