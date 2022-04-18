package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public class CachingFileSystemLocationSnapshotHasher implements FileSystemLocationSnapshotHasher {
    private final FileSystemLocationSnapshotHasher delegate;
    private final ResourceSnapshotterCacheService resourceSnapshotterCacheService;
    private final HashCode delegateConfigurationHash;

    public CachingFileSystemLocationSnapshotHasher(FileSystemLocationSnapshotHasher delegate, ResourceSnapshotterCacheService resourceSnapshotterCacheService) {
        this.delegate = delegate;
        this.resourceSnapshotterCacheService = resourceSnapshotterCacheService;
        Hasher hasher = Hashes.newHasher();
        appendConfigurationToHasher(hasher);
        this.delegateConfigurationHash = hasher.hash();
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        delegate.appendConfigurationToHasher(hasher);
    }

    @Nullable
    @Override
    public HashCode hash(FileSystemLocationSnapshot snapshot) throws IOException {
        return resourceSnapshotterCacheService.hashFile(snapshot, delegate, delegateConfigurationHash);
    }
}