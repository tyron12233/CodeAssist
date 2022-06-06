package com.tyron.builder.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintHashingStrategy;
import com.tyron.builder.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.RelativePathTracker;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;

/**
 * Fingerprint file system snapshots normalizing the path to the relative path in a hierarchy.
 *
 * File names for root directories are ignored. For root files, the file name is used as normalized path.
 */
public class RelativePathFingerprintingStrategy extends AbstractDirectorySensitiveFingerprintingStrategy {
    public static final String IDENTIFIER = "RELATIVE_PATH";

    private final Interner<String> stringInterner;
    private final FileSystemLocationSnapshotHasher normalizedContentHasher;

    public RelativePathFingerprintingStrategy(Interner<String> stringInterner, DirectorySensitivity directorySensitivity, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(IDENTIFIER, directorySensitivity, normalizedContentHasher);
        this.stringInterner = stringInterner;
        this.normalizedContentHasher = normalizedContentHasher;
    }

    public RelativePathFingerprintingStrategy(Interner<String> stringInterner, DirectorySensitivity directorySensitivity) {
        this(stringInterner, directorySensitivity, FileSystemLocationSnapshotHasher.DEFAULT);
    }

    @Override
    public String normalizePath(FileSystemLocationSnapshot snapshot) {
        if (snapshot.getType() == FileType.Directory) {
            return "";
        } else {
            return snapshot.getName();
        }
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> collectFingerprints(FileSystemSnapshot roots) {
        ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder = ImmutableMap.builder();
        HashSet<String> processedEntries = new HashSet<>();
        roots.accept(new RelativePathTracker(), (snapshot, relativePath) -> {
            String absolutePath = snapshot.getAbsolutePath();
            if (processedEntries.add(absolutePath) && getDirectorySensitivity().shouldFingerprint(snapshot)) {
                FileSystemLocationFingerprint fingerprint;
                if (relativePath.isRoot()) {
                    if (snapshot.getType() == FileType.Directory) {
                        return SnapshotVisitResult.CONTINUE;
                    } else {
                        fingerprint = fingerprint(snapshot.getName(), snapshot.getType(), snapshot);
                    }
                } else {
                    fingerprint = fingerprint(stringInterner.intern(relativePath.toRelativePath()), snapshot.getType(), snapshot);
                }

                if (fingerprint != null) {
                    builder.put(absolutePath, fingerprint);
                }
            }
            return SnapshotVisitResult.CONTINUE;
        });
        return builder.build();
    }

    @Nullable
    FileSystemLocationFingerprint fingerprint(String name, FileType type, FileSystemLocationSnapshot snapshot) {
        HashCode normalizedContentHash = getNormalizedContentHash(snapshot, normalizedContentHasher);
        return normalizedContentHash == null ? null : new DefaultFileSystemLocationFingerprint(name, type, normalizedContentHash);
    }

    @Override
    public FingerprintHashingStrategy getHashingStrategy() {
        return FingerprintHashingStrategy.SORT;
    }
}