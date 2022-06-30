package com.tyron.builder.internal.classpath;

import com.tyron.builder.cache.GlobalCacheLocations;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.util.internal.GFileUtils;

import java.io.File;

public class CopyingClasspathFileTransformer implements ClasspathFileTransformer {
    private final GlobalCacheLocations globalCacheLocations;

    public CopyingClasspathFileTransformer(GlobalCacheLocations globalCacheLocations) {
        this.globalCacheLocations = globalCacheLocations;
    }

    @Override
    public File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir) {
        // Copy files into the cache, if it is possible that loading the file in a ClassLoader may cause locking problems if the file is deleted

        if (sourceSnapshot.getType() != FileType.RegularFile) {
            // Directories are ok to use outside the cache
            return source;
        }
        if (globalCacheLocations.isInsideGlobalCache(source.getAbsolutePath())) {
            // The global caches are additive only, so we can use it directly since it shouldn't be deleted or changed during the build.
            return source;
        }

        // Copy the file into the cache
        File cachedFile = new File(cacheDir, "o_" + sourceSnapshot.getHash().toString() + '/' + source.getName());
        if (!cachedFile.isFile()) {
            // Just copy the jar
            GFileUtils.copyFile(source, cachedFile);
        }
        return cachedFile;
    }
}
