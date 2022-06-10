package org.gradle.internal.fingerprint.impl;

import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.RelativePathInputNormalizer;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.internal.cache.StringInterner;


public class RelativePathFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public RelativePathFileCollectionFingerprinter(StringInterner stringInterner, DirectorySensitivity directorySensitivity, FileCollectionSnapshotter fileCollectionSnapshotter, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(new RelativePathFingerprintingStrategy(stringInterner, directorySensitivity, normalizedContentHasher), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return RelativePathInputNormalizer.class;
    }
}