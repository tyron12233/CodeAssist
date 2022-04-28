package com.tyron.builder.internal.fingerprint.hashing;

import com.google.common.hash.HashCode;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public interface RegularFileSnapshotContextHasher {

    /**
     * Returns {@code null} if the file should be ignored.
     */
    @Nullable
    HashCode hash(RegularFileSnapshotContext snapshotContext) throws IOException;
}