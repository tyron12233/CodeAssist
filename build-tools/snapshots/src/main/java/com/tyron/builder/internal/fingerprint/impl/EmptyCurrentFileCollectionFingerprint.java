package org.gradle.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.hash.Hashes;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.util.Collections;
import java.util.Map;

public class EmptyCurrentFileCollectionFingerprint implements CurrentFileCollectionFingerprint {

    private static final HashCode SIGNATURE = Hashes.signature(EmptyCurrentFileCollectionFingerprint.class);

    private final String identifier;

    public EmptyCurrentFileCollectionFingerprint(String identifier) {
        this.identifier = identifier;
    }

    @Override
    public HashCode getHash() {
        return SIGNATURE;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> getFingerprints() {
        return Collections.emptyMap();
    }

    @Override
    public FileSystemSnapshot getSnapshot() {
        return FileSystemSnapshot.EMPTY;
    }

    @Override
    public ImmutableMultimap<String, HashCode> getRootHashes() {
        return ImmutableMultimap.of();
    }

    @Override
    public boolean wasCreatedWithStrategy(FingerprintingStrategy strategy) {
        return false;
    }

    @Override
    public String getStrategyIdentifier() {
        return identifier;
    }

    @Override
    public FileCollectionFingerprint archive(ArchivedFileCollectionFingerprintFactory factory) {
        return FileCollectionFingerprint.EMPTY;
    }

    @Override
    public String toString() {
        return identifier + "{EMPTY}";
    }
}