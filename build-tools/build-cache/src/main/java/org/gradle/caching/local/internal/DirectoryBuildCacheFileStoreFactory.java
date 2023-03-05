package org.gradle.caching.local.internal;

import org.gradle.internal.resource.local.PathKeyFileStore;

import java.io.File;

public interface DirectoryBuildCacheFileStoreFactory {
    PathKeyFileStore createFileStore(File baseDir);
}
