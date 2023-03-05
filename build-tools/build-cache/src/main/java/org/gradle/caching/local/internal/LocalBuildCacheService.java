package org.gradle.caching.local.internal;


import org.gradle.api.Action;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;

import java.io.Closeable;
import java.io.File;

/**
 * A build cache service that is capable of handling local files directly. The direct access
 * allows more optimized implementations than the more general {@link BuildCacheService}
 * interface.
 */
public interface LocalBuildCacheService extends BuildCacheTempFileStore, Closeable {

    /**
     * Loads a cache artifact from a local file store. If a result is found the {@code reader} is executed.
     */
    void loadLocally(BuildCacheKey key, Action<? super File> reader);

    /**
     * Store the given file in the local file store as a cache artifact.
     */
    void storeLocally(BuildCacheKey key, File file);

    @Override
    void close();
}
