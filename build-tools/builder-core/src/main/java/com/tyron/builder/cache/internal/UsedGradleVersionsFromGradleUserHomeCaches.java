package com.tyron.builder.cache.internal;

import com.google.common.collect.Sets;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.util.GradleVersion;

import java.util.SortedSet;

public class UsedGradleVersionsFromGradleUserHomeCaches implements UsedGradleVersions {

    private final VersionSpecificCacheDirectoryScanner directoryScanner;

    public UsedGradleVersionsFromGradleUserHomeCaches(GlobalScopedCache globalScopedCache) {
        directoryScanner = new VersionSpecificCacheDirectoryScanner(globalScopedCache.getRootDir());
    }

    @Override
    public SortedSet<GradleVersion> getUsedGradleVersions() {
        SortedSet<GradleVersion> result = Sets.newTreeSet();
        for (VersionSpecificCacheDirectory cacheDir : directoryScanner.getExistingDirectories()) {
            result.add(cacheDir.getVersion());
        }
        return result;
    }
}
