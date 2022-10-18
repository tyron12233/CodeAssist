package org.gradle.caching.internal.service;

import org.gradle.caching.BuildCacheService;
import org.gradle.caching.local.internal.LocalBuildCacheService;

import org.jetbrains.annotations.Nullable;

public final class BuildCacheServicesConfiguration {

    private final LocalBuildCacheService local;
    private final boolean localPush;

    private final BuildCacheService remote;
    private final boolean remotePush;

    public BuildCacheServicesConfiguration(
            @Nullable LocalBuildCacheService local,
            boolean localPush,
            @Nullable BuildCacheService remote,
            boolean remotePush
    ) {
        this.remote = remote;
        this.remotePush = remotePush;
        this.local = local;
        this.localPush = localPush;
    }

    @Nullable
    public LocalBuildCacheService getLocal() {
        return local;
    }

    public boolean isLocalPush() {
        return localPush;
    }

    @Nullable
    public BuildCacheService getRemote() {
        return remote;
    }

    public boolean isRemotePush() {
        return remotePush;
    }
}