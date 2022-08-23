package org.gradle.internal.fingerprint.impl;

import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.IgnoredPathInputNormalizer;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;

public class IgnoredPathFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public IgnoredPathFileCollectionFingerprinter(FileCollectionSnapshotter fileCollectionSnapshotter, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(new org.gradle.internal.fingerprint.impl.IgnoredPathFingerprintingStrategy(normalizedContentHasher), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return IgnoredPathInputNormalizer.class;
    }
}