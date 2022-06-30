package com.tyron.builder.internal.fingerprint.impl;

import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintingStrategy;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

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