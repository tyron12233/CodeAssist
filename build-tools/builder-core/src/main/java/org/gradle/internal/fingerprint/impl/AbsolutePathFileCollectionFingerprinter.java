package org.gradle.internal.fingerprint.impl;

import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.AbsolutePathInputNormalizer;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.api.tasks.FileNormalizer;

@ServiceScope(Scopes.BuildSession.class)
public class AbsolutePathFileCollectionFingerprinter extends AbstractFileCollectionFingerprinter {

    public AbsolutePathFileCollectionFingerprinter(DirectorySensitivity directorySensitivity, FileCollectionSnapshotter fileCollectionSnapshotter, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(new org.gradle.internal.fingerprint.impl.AbsolutePathFingerprintingStrategy(directorySensitivity, normalizedContentHasher), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return AbsolutePathInputNormalizer.class;
    }
}