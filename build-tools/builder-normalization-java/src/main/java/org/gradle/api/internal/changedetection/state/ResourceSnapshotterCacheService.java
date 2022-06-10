package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.fingerprint.hashing.RegularFileSnapshotContext;
import org.gradle.internal.fingerprint.hashing.RegularFileSnapshotContextHasher;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public interface ResourceSnapshotterCacheService {
    @Nullable
    HashCode hashFile(FileSystemLocationSnapshot snapshot, FileSystemLocationSnapshotHasher hasher, HashCode configurationHash) throws IOException;

    @Nullable
    HashCode hashFile(RegularFileSnapshotContext fileSnapshotContext, RegularFileSnapshotContextHasher hasher, HashCode configurationHash) throws IOException;
}