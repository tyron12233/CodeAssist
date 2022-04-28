package com.tyron.builder.caching.internal;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.caching.BuildCacheServiceFactory;
import com.tyron.builder.caching.configuration.BuildCache;
import com.tyron.builder.caching.configuration.BuildCacheConfiguration;
import com.tyron.builder.caching.local.DirectoryBuildCache;

import org.jetbrains.annotations.Nullable;

@ServiceScope(Scopes.Build.class)
public interface BuildCacheConfigurationInternal extends BuildCacheConfiguration {
    /**
     * Finds a build cache implementation factory class for the given configuration type.
     */
    <T extends BuildCache> Class<? extends BuildCacheServiceFactory<T>> getBuildCacheServiceFactoryType(Class<T> configurationType);

    /**
     * Replaces local directory build cache.
     */
    void setLocal(DirectoryBuildCache local);

    /**
     * Replaces remote build cache.
     */
    void setRemote(@Nullable BuildCache remote);
}