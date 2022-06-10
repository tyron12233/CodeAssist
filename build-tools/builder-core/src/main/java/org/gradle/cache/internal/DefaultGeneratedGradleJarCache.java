package org.gradle.cache.internal;

import org.gradle.api.Action;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.GlobalCache;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.scopes.GlobalScopedCache;

import java.io.Closeable;
import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

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
