package org.gradle.internal.fingerprint.impl;

import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.NameOnlyInputNormalizer;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;

public class NameOnlyFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public NameOnlyFileCollectionFingerprinter(DirectorySensitivity directorySensitivity, FileCollectionSnapshotter fileCollectionSnapshotter, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(new org.gradle.internal.fingerprint.impl.NameOnlyFingerprintingStrategy(directorySensitivity, normalizedContentHasher), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return NameOnlyInputNormalizer.class;
    }
}