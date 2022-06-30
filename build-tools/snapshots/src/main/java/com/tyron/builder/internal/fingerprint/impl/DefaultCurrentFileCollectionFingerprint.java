package com.tyron.builder.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintHashingStrategy;
import com.tyron.builder.internal.fingerprint.FingerprintingStrategy;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotUtil;

import javax.annotation.Nullable;
import java.util.Map;

public class DefaultCurrentFileCollectionFingerprint implements CurrentFileCollectionFingerprint {

    private final Map<String, FileSystemLocationFingerprint> fingerprints;
    private final FingerprintHashingStrategy hashingStrategy;
    private final String identifier;
    private final FileSystemSnapshot roots;
    private final ImmutableMultimap<String, HashCode> rootHashes;
    private final HashCode strategyConfigurationHash;
    private HashCode hash;

    public static CurrentFileCollectionFingerprint from(FileSystemSnapshot roots, FingerprintingStrategy strategy, @Nullable  FileCollectionFingerprint candidate) {
        if (roots == FileSystemSnapshot.EMPTY) {
            return strategy.getEmptyFingerprint();
        }

        ImmutableMultimap<String, HashCode> rootHashes = SnapshotUtil.getRootHashes(roots);
        Map<String, FileSystemLocationFingerprint> fingerprints;
        if (candidate != null
            && candidate.wasCreatedWithStrategy(strategy)
            && equalRootHashes(candidate.getRootHashes(), rootHashes)
        ) {
            fingerprints = candidate.getFingerprints();
        } else {
            fingerprints = strategy.collectFingerprints(roots);
        }
        if (fingerprints.isEmpty()) {
            return strategy.getEmptyFingerprint();
        }
        return new DefaultCurrentFileCollectionFingerprint(fingerprints, roots, rootHashes, strategy);
    }

    private static boolean equalRootHashes(ImmutableMultimap<String, HashCode> first, ImmutableMultimap<String, HashCode> second) {
        // We cannot use `first.equals(second)`, since the order of the root hashes matters
        return Iterables.elementsEqual(first.entries(), second.entries());
    }

    private DefaultCurrentFileCollectionFingerprint(
            Map<String, FileSystemLocationFingerprint> fingerprints,
            FileSystemSnapshot roots,
            ImmutableMultimap<String, HashCode> rootHashes,
            FingerprintingStrategy strategy
    ) {
        this.fingerprints = fingerprints;
        this.identifier = strategy.getIdentifier();
        this.hashingStrategy = strategy.getHashingStrategy();
        this.strategyConfigurationHash = strategy.getConfigurationHash();
        this.roots = roots;
        this.rootHashes = rootHashes;
    }

    @Override
    public HashCode getHash() {
        if (hash == null) {
            Hasher hasher = Hashes.newHasher();
            hashingStrategy.appendToHasher(hasher, fingerprints.values());
            hash = hasher.hash();
        }
        return hash;
    }

    @Override
    public boolean isEmpty() {
        // We'd have created an EmptyCurrentFileCollectionFingerprint if there were no file fingerprints
        return false;
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

    @Override
    public String getStrategyIdentifier() {
        return identifier;
    }

    @Override
    public FileSystemSnapshot getSnapshot() {
        return roots;
    }

    @Override
    public FileCollectionFingerprint archive(CurrentFileCollectionFingerprint.ArchivedFileCollectionFingerprintFactory factory) {
        return factory.createArchivedFileCollectionFingerprint(fingerprints, rootHashes, strategyConfigurationHash);
    }

    @Override
    public String toString() {
        return identifier + fingerprints;
    }
}