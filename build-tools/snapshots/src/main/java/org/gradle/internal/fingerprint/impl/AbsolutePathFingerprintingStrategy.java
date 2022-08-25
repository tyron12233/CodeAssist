package org.gradle.internal.fingerprint.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintHashingStrategy;
import org.gradle.internal.fingerprint.FingerprintingStrategy;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import org.gradle.internal.snapshot.SnapshotVisitResult;

import java.util.HashSet;
import java.util.Map;

/**
 * Fingerprint files without path or content normalization.
 */
public class AbsolutePathFingerprintingStrategy extends AbstractDirectorySensitiveFingerprintingStrategy {
    public static final FingerprintingStrategy DEFAULT = new AbsolutePathFingerprintingStrategy(DirectorySensitivity.DEFAULT);
    public static final FingerprintingStrategy IGNORE_DIRECTORIES = new AbsolutePathFingerprintingStrategy(DirectorySensitivity.IGNORE_DIRECTORIES);

    public static final String IDENTIFIER = "ABSOLUTE_PATH";

    private final FileSystemLocationSnapshotHasher normalizedContentHasher;

    public AbsolutePathFingerprintingStrategy(DirectorySensitivity directorySensitivity, FileSystemLocationSnapshotHasher normalizedContentHasher) {
        super(IDENTIFIER, directorySensitivity, normalizedContentHasher);
        this.normalizedContentHasher = normalizedContentHasher;
    }

    private AbsolutePathFingerprintingStrategy(DirectorySensitivity directorySensitivity) {
        this(directorySensitivity, FileSystemLocationSnapshotHasher.DEFAULT);
    }

    @Override
    public String normalizePath(FileSystemLocationSnapshot snapshot) {
        return snapshot.getAbsolutePath();
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
                    HashCode normalizedContentHash = getNormalizedContentHash(snapshot, normalizedContentHasher);
                    if (normalizedContentHash != null) {
                        builder.put(absolutePath, new DefaultFileSystemLocationFingerprint(snapshot.getAbsolutePath(), snapshot.getType(), normalizedContentHash));
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