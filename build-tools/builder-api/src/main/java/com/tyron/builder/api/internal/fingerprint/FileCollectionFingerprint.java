package com.tyron.builder.api.internal.fingerprint;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

import java.util.Map;

/**
 * An immutable snapshot of some aspects of the contents and meta-data of a collection of files or directories.
 */
public interface FileCollectionFingerprint {

    /**
     * The underlying fingerprints.
     */
    Map<String, FileSystemLocationFingerprint> getFingerprints();

    /**
     * The Merkle hashes of the roots which make up this file collection fingerprint.
     */
    ImmutableMultimap<String, HashCode> getRootHashes();

    boolean wasCreatedWithStrategy(FingerprintingStrategy strategy);

    FileCollectionFingerprint EMPTY = new FileCollectionFingerprint() {
        @Override
        public Map<String, FileSystemLocationFingerprint> getFingerprints() {
            return ImmutableSortedMap.of();
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
        public String toString() {
            return "EMPTY";
        }
    };
}
