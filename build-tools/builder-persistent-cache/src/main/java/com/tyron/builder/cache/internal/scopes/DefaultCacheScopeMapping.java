package com.tyron.builder.cache.internal.scopes;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.VersionStrategy;
import com.tyron.builder.util.GradleVersion;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.regex.Pattern;

public class DefaultCacheScopeMapping implements CacheScopeMapping {

    @VisibleForTesting
    public static final String GLOBAL_CACHE_DIR_NAME = "caches";
    private static final Pattern CACHE_KEY_NAME_PATTERN = Pattern.compile("\\p{Alpha}+[-/.\\w]*");

    private final File globalCacheDir;
    private final GradleVersion version;

    public DefaultCacheScopeMapping(File rootDir, GradleVersion version) {
        this.globalCacheDir = rootDir;
        this.version = version;
    }

    @Override
    public File getBaseDirectory(@Nullable File baseDir,
                                 String key,
                                 VersionStrategy versionStrategy) {
        if (!CACHE_KEY_NAME_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(String.format("Unsupported cache key '%s'.", key));
        }
        File cacheRootDir = getRootDirectory(baseDir);
        return getCacheDir(cacheRootDir, versionStrategy, key);
    }

    private File getRootDirectory(@Nullable File scope) {
        if (scope == null) {
            return globalCacheDir;
        } else {
            return scope;
        }
    }

    private File getCacheDir(File rootDir, VersionStrategy versionStrategy, String subDir) {
        switch (versionStrategy) {
            case CachePerVersion:
                return new File(rootDir, version.getVersion() + "/" + subDir);
            case SharedCache:
                return new File(rootDir, subDir);
            default:
                throw new IllegalArgumentException();
        }
    }
}