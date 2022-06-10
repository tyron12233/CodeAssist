package org.gradle.cache.internal;

import org.gradle.cache.GlobalCache;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.internal.file.FileHierarchySet;

import java.io.File;
import java.util.List;

public class DefaultGlobalCacheLocations implements GlobalCacheLocations {
    private final FileHierarchySet globalCacheRoots;

    public DefaultGlobalCacheLocations(List<GlobalCache> globalCaches) {
        FileHierarchySet globalCacheRoots = FileHierarchySet.empty();
        for (GlobalCache globalCache : globalCaches) {
            for (File file : globalCache.getGlobalCacheRoots()) {
                globalCacheRoots = globalCacheRoots.plus(file);
            }
        }
        this.globalCacheRoots = globalCacheRoots;
    }

    @Override
    public boolean isInsideGlobalCache(String path) {
        return globalCacheRoots.contains(path);
    }

    @Override
    public String toString() {
        return globalCacheRoots.toString();
    }
}
