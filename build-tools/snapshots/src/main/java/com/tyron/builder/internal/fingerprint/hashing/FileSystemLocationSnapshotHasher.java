package com.tyron.builder.internal.fingerprint.hashing;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public interface FileSystemLocationSnapshotHasher extends ConfigurableNormalizer {
    /**
     * Returns {@code null} if the file should be ignored.
     */
    @Nullable
    HashCode hash(FileSystemLocationSnapshot snapshot) throws IOException;

    FileSystemLocationSnapshotHasher DEFAULT = new FileSystemLocationSnapshotHasher() {
        @Nullable
        @Override
        public HashCode hash(FileSystemLocationSnapshot snapshot) {
            return snapshot.getHash();
        }

        @Override
        public void appendConfigurationToHasher(Hasher hasher) {
            hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        }
    };
}