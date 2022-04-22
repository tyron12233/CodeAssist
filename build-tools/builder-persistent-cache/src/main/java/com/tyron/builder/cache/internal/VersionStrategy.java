package com.tyron.builder.cache.internal;

public enum VersionStrategy {
    /**
     * A separate cache instance for each Gradle version. This is the default.
     */
    CachePerVersion,
    /**
     * A single cache instance shared by all Gradle versions. It is the caller's responsibility to make sure that this is shared only with
     * those versions of Gradle that are compatible with the cache implementation and contents.
     */
    SharedCache
}