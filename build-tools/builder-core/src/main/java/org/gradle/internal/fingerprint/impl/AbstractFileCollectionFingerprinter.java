package org.gradle.internal.fingerprint.impl;

import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import org.jetbrains.annotations.Nullable;

/**
 * Responsible for calculating a {@link FileCollectionFingerprint} for a particular {@link FileCollection}.
 */
@NonNullApi
public abstract class AbstractFileCollectionFingerprinter implements FileCollectionFingerprinter {

    private final FileCollectionSnapshotter fileCollectionSnapshotter;
    private final FingerprintingStrategy fingerprintingStrategy;

    public AbstractFileCollectionFingerprinter(FingerprintingStrategy fingerprintingStrategy, FileCollectionSnapshotter fileCollectionSnapshotter) {
        this.fingerprintingStrategy = fingerprintingStrategy;
        this.fileCollectionSnapshotter = fileCollectionSnapshotter;
    }

    @Override
    public CurrentFileCollectionFingerprint fingerprint(FileCollection files) {
        FileCollectionSnapshotter.Result snapshotResult = fileCollectionSnapshotter.snapshot(files);
        return fingerprint(snapshotResult.getSnapshot(), null);
    }

    @Override
    public CurrentFileCollectionFingerprint fingerprint(FileSystemSnapshot snapshot, @Nullable FileCollectionFingerprint previousFingerprint) {
        return DefaultCurrentFileCollectionFingerprint.from(snapshot, fingerprintingStrategy, previousFingerprint);
    }

    @Override
    public CurrentFileCollectionFingerprint empty() {
        return fingerprintingStrategy.getEmptyFingerprint();
    }

    @Override
    public String normalizePath(FileSystemLocationSnapshot root) {
        return fingerprintingStrategy.normalizePath(root);
    }
}