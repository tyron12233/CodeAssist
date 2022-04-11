package com.tyron.builder.api.internal.fingerprint;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;

import java.util.Map;

/**
 * A file collection fingerprint taken during this build.
 */
public interface CurrentFileCollectionFingerprint extends FileCollectionFingerprint {
    /**
     * Returns the combined hash of the contents of this {@link CurrentFileCollectionFingerprint}.
     */
    HashCode getHash();

    /**
     * An identifier for the strategy.
     *
     * Used to select a compare strategy.
     */
    String getStrategyIdentifier();

    /**
     * Returns the snapshot used to capture these fingerprints.
     */
    FileSystemSnapshot getSnapshot();

    boolean isEmpty();

    /**
     * Archive the file collection fingerprint.
     *
     * @return a file collection fingerprint which can be archived.
     */
    FileCollectionFingerprint archive(ArchivedFileCollectionFingerprintFactory factory);

    interface ArchivedFileCollectionFingerprintFactory {
        FileCollectionFingerprint createArchivedFileCollectionFingerprint(Map<String, FileSystemLocationFingerprint> fingerprints, ImmutableMultimap<String, HashCode> rootHashes, HashCode strategyConfigurationHash);
    }
}