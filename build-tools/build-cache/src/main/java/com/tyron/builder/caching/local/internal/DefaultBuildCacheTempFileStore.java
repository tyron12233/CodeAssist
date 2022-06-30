package com.tyron.builder.caching.local.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.caching.BuildCacheKey;

import java.io.File;

public class DefaultBuildCacheTempFileStore implements BuildCacheTempFileStore {

    private final TemporaryFileProvider temporaryFileProvider;

    public DefaultBuildCacheTempFileStore(TemporaryFileProvider temporaryFileProvider) {
        this.temporaryFileProvider = temporaryFileProvider;
    }

    @Override
    public void withTempFile(BuildCacheKey key, Action<? super File> action) {
        String hashCode = key.getHashCode();
        File tempFile = null;
        try {
            tempFile = temporaryFileProvider.createTemporaryFile(hashCode + "-", PARTIAL_FILE_SUFFIX);
            action.execute(tempFile);
        } finally {
            GFileUtils.deleteQuietly(tempFile);
        }
    }
}