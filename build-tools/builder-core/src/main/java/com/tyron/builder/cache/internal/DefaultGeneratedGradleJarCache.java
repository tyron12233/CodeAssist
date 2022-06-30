package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.GlobalCache;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;

import java.io.Closeable;
import java.io.File;
import java.util.Collections;
import java.util.List;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

public class DefaultGeneratedGradleJarCache implements GeneratedGradleJarCache, Closeable, GlobalCache {
    private final PersistentCache cache;
    private final String gradleVersion;

    public DefaultGeneratedGradleJarCache(GlobalScopedCache cacheRepository, String gradleVersion) {
        this.cache = cacheRepository.cache(CACHE_KEY)
            .withDisplayName(CACHE_DISPLAY_NAME)
            .withLockOptions(mode(FileLockManager.LockMode.OnDemand))
            .open();
        this.gradleVersion = gradleVersion;
    }

    @Override
    public File get(final String identifier, final Action<File> creator) {
        final File jarFile = jarFile(identifier);
        cache.useCache(() -> {
            if (!jarFile.exists()) {
                creator.execute(jarFile);
            }
        });
        return jarFile;
    }

    @Override
    public void close() {
        cache.close();
    }

    private File jarFile(String identifier) {
        return new File(cache.getBaseDir(), "gradle-" + identifier + "-" + gradleVersion + ".jar");
    }

    @Override
    public List<File> getGlobalCacheRoots() {
        return Collections.singletonList(cache.getBaseDir());
    }
}
