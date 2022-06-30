package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.hashing.ZipEntryContext;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class IgnoringResourceHasher implements ResourceHasher {
    private final ResourceHasher delegate;
    private final ResourceFilter resourceFilter;

    public IgnoringResourceHasher(ResourceHasher delegate, ResourceFilter resourceFilter) {
        this.delegate = delegate;
        this.resourceFilter = resourceFilter;
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        delegate.appendConfigurationToHasher(hasher);
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        resourceFilter.appendConfigurationToHasher(hasher);
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshotContext snapshotContext) throws IOException {
        return resourceFilter.shouldBeIgnored(snapshotContext.getRelativePathSegments()) ? null : delegate.hash(snapshotContext);
    }

    @Nullable
    @Override
    public HashCode hash(ZipEntryContext zipEntryContext) throws IOException {
        return resourceFilter.shouldBeIgnored(zipEntryContext.getRelativePathSegments()) ? null : delegate.hash(zipEntryContext);
    }
}