package com.tyron.builder.caching.local.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.caching.BuildCacheKey;

import java.io.File;

public interface BuildCacheTempFileStore {

    String PARTIAL_FILE_SUFFIX = ".part";

    /**
     * Run the given action with a temp file allocated based on the given cache key.
     * The temp file will be deleted once the action is completed.
     */
    void withTempFile(BuildCacheKey key, Action<? super File> action);

}