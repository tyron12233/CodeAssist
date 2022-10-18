package org.gradle.caching.internal.controller.service;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.caching.internal.origin.OriginMetadata;

public interface BuildCacheLoadResult {
    long getArtifactEntryCount();

    OriginMetadata getOriginMetadata();

    ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots();
}