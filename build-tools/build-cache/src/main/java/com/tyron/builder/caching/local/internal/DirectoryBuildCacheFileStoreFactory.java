package com.tyron.builder.caching.local.internal;

import com.tyron.builder.internal.resource.local.PathKeyFileStore;

import java.io.File;

public interface DirectoryBuildCacheFileStoreFactory {
    PathKeyFileStore createFileStore(File baseDir);
}
