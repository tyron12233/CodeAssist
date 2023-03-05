package org.gradle.caching.local.internal;

import org.gradle.api.Action;
import org.gradle.caching.BuildCacheKey;

import java.io.File;

public interface BuildCacheTempFileStore {

    String PARTIAL_FILE_SUFFIX = ".part";

    /**
     * Run the given action with a temp file allocated based on the given cache key.
     * The temp file will be deleted once the action is completed.
     */
    void withTempFile(BuildCacheKey key, Action<? super File> action);

}