package org.gradle.caching.internal;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.caching.BuildCacheServiceFactory;
import org.gradle.caching.configuration.BuildCache;
import org.gradle.caching.configuration.BuildCacheConfiguration;
import org.gradle.caching.local.DirectoryBuildCache;

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