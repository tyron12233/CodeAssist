package org.gradle.caching.local.internal;

import org.gradle.api.Action;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.util.internal.GFileUtils;
import org.gradle.caching.BuildCacheKey;

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