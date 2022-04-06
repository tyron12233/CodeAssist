package com.tyron.builder.api.internal.fingerprint.impl;

import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.RelativePathInputNormalizer;
import com.tyron.builder.api.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.cache.StringInterner;


public class RelativePathFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public RelativePathFileCollectionFingerprinter(StringInterner stringInterner, DirectorySensitivity directorySensitivity, FileCollectionSnapshotter fileCollectionSnapshotter, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(new RelativePathFingerprintingStrategy(stringInterner, directorySensitivity, normalizedContentHasher), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return RelativePathInputNormalizer.class;
    }
}