package com.tyron.builder.internal.fingerprint.impl;


import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintHashingStrategy;
import com.tyron.builder.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import java.util.HashSet;
import java.util.Map;

/**
 * Fingerprint files normalizing the path to the file name.
 *
 * File names for root directories are ignored.
 */
public class NameOnlyFingerprintingStrategy extends AbstractDirectorySensitiveFingerprintingStrategy {
    public static final NameOnlyFingerprintingStrategy DEFAULT = new NameOnlyFingerprintingStrategy(DirectorySensitivity.DEFAULT);
    public static final NameOnlyFingerprintingStrategy IGNORE_DIRECTORIES = new NameOnlyFingerprintingStrategy(DirectorySensitivity.IGNORE_DIRECTORIES);
    public static final String IDENTIFIER = "NAME_ONLY";
    private final FileSystemLocationSnapshotHasher normalizedContentHasher;

    public NameOnlyFingerprintingStrategy(DirectorySensitivity directorySensitivity, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(IDENTIFIER, directorySensitivity, normalizedContentHasher);
        this.normalizedContentHasher = normalizedContentHasher;
    }

    private NameOnlyFingerprintingStrategy(DirectorySensitivity directorySensitivity) {
        this(directorySensitivity, FileSystemLocationSnapshotHasher.DEFAULT);
    }

    @Override
    public String normalizePath(FileSystemLocationSnapshot snapshot) {
        return snapshot.getName();
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> collectFingerprints(FileSystemSnapshot roots) {
        ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder = ImmutableMap.builder();
        HashSet<String> processedEntries = new HashSet<>();
        roots.accept(new RootTrackingFileSystemSnapshotHierarchyVisitor() {
            @Override
            public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
                String absolutePath = snapshot.getAbsolutePath();
                if (processedEntries.add(absolutePath) && getDirectorySensitivity().shouldFingerprint(snapshot)) {
                    if (isRoot && snapshot.getType() == FileType.Directory) {
                        builder.put(absolutePath, IgnoredPathFileSystemLocationFingerprint.DIRECTORY);
                    } else {
                        HashCode normalizedContentHash = getNormalizedContentHash(snapshot, normalizedContentHasher);
                        if (normalizedContentHash != null) {
                            builder.put(absolutePath, new DefaultFileSystemLocationFingerprint(snapshot.getName(), snapshot.getType(), normalizedContentHash));
                        }
                    }
                }
                return SnapshotVisitResult.CONTINUE;
            }
        });
        return builder.build();
    }

    @Override
    public FingerprintHashingStrategy getHashingStrategy() {
        return FingerprintHashingStrategy.SORT;
    }
}