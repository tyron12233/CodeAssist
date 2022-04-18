package com.tyron.builder.cache.internal;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface CacheScopeMapping {
    File getBaseDirectory(@Nullable File baseDir, String key, VersionStrategy versionStrategy);
}