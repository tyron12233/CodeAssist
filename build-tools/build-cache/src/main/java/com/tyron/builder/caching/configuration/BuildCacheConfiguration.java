package com.tyron.builder.caching.configuration;

import com.tyron.builder.api.Action;
import com.tyron.builder.caching.BuildCacheService;
import com.tyron.builder.caching.BuildCacheServiceFactory;
import com.tyron.builder.caching.local.DirectoryBuildCache;

import org.jetbrains.annotations.Nullable;

/**
 * Configuration for the <a href="https://docs.gradle.org/current/userguide/build_cache.html" target="_top">build cache</a> for an entire Gradle build.
 *
 * @since 3.5
 */
public interface BuildCacheConfiguration {

    /**
     * Registers a custom build cache type.
     *
     * @param configurationType Configuration type used to provide parameters to a {@link BuildCacheService}
     * @param buildCacheServiceFactoryType Implementation type of {@link BuildCacheServiceFactory} that is used to create a {@code BuildCacheService}
     */
    <T extends BuildCache> void registerBuildCacheService(Class<T> configurationType, Class<? extends BuildCacheServiceFactory<? super T>> buildCacheServiceFactoryType);

    /**
     * Returns the local directory cache configuration.
     */
    DirectoryBuildCache getLocal();

    /**
     * Executes the given action against the local configuration.
     *
     * @param configuration the action to execute against the local cache configuration.
     */
    void local(Action<? super DirectoryBuildCache> configuration);

    /**
     * Returns the remote cache configuration.
     */
    @Nullable
    BuildCache getRemote();

    /**
     * Configures a remote cache with the given type.
     * <p>
     * If a remote build cache has already been configured with a different type, this method replaces it.
     * </p>
     * <p>
     * Storing ("push") in the remote build cache is disabled by default.
     * </p>
     * @param type the type of remote cache to configure.
     *
     */
    <T extends BuildCache> T remote(Class<T> type);

    /**
     * Configures a remote cache with the given type.
     * <p>
     * If a remote build cache has already been configured with a <b>different</b> type, this method replaces it.
     * </p>
     * <p>
     * If a remote build cache has already been configured with the <b>same</b>, this method configures it.
     * </p>
     * <p>
     * Storing ("push") in the remote build cache is disabled by default.
     * </p>
     * @param type the type of remote cache to configure.
     * @param configuration the configuration to execute against the remote cache.
     *
     */
    <T extends BuildCache> T remote(Class<T> type, Action<? super T> configuration);

    /**
     * Executes the given action against the currently configured remote cache.
     *
     * @param configuration the action to execute against the currently configured remote cache.
     *
     * @throws IllegalStateException If no remote cache has been assigned yet
     */
    void remote(Action<? super BuildCache> configuration);
}