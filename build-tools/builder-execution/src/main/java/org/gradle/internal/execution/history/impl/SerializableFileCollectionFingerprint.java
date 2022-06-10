package org.gradle.internal.execution.history.impl;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.hash.HashCode;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintingStrategy;

import java.util.Map;

public class SerializableFileCollectionFingerprint implements FileCollectionFingerprint {

    private final Map<String, FileSystemLocationFingerprint> fingerprints;
    private final ImmutableMultimap<String, HashCode> rootHashes;
    private final HashCode strategyConfigurationHash;

    public SerializableFileCollectionFingerprint(Map<String, FileSystemLocationFingerprint> fingerprints, ImmutableMultimap<String, HashCode> rootHashes, HashCode strategyConfigurationHash) {
        this.fingerprints = fingerprints;
        this.rootHashes = rootHashes;
        this.strategyConfigurationHash = strategyConfigurationHash;
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> getFingerprints() {
        return fingerprints;
    }

    @Override
    public ImmutableMultimap<String, HashCode> getRootHashes() {
        return rootHashes;
    }

    @Override
    public boolean wasCreatedWithStrategy(FingerprintingStrategy strategy) {
        return strategy.getConfigurationHash().equals(strategyConfigurationHash);
    }

    public HashCode getStrategyConfigurationHash() {
        return strategyConfigurationHash;
    }
}