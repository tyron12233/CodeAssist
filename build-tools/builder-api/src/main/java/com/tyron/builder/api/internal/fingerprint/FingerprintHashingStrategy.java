package com.tyron.builder.api.internal.fingerprint;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;

import java.util.Collection;

/**
 * Strategy for appending a collection of fingerprints to a hasher.
 */
public enum FingerprintHashingStrategy {
    SORT {
        @Override
        public void appendToHasher(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints) {
            ImmutableList<FileSystemLocationFingerprint> sortedFingerprints = ImmutableList.sortedCopyOf(fingerprints);
            appendCollectionToHasherKeepingOrder(hasher, sortedFingerprints);
        }
    },
    KEEP_ORDER {
        @Override
        public void appendToHasher(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints) {
            appendCollectionToHasherKeepingOrder(hasher, fingerprints);
        }
    };

    public abstract void appendToHasher(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints);

    protected void appendCollectionToHasherKeepingOrder(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints) {
        for (FileSystemLocationFingerprint fingerprint : fingerprints) {
            fingerprint.appendToHasher(hasher);
        }
    }
}