package com.tyron.builder.caching.internal.controller.service;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

public interface BuildCacheLoadResult {
    long getArtifactEntryCount();

    OriginMetadata getOriginMetadata();

    ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots();
}