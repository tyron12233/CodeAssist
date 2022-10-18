package org.gradle.initialization.layout;

import static org.gradle.cache.internal.scopes.DefaultCacheScopeMapping.GLOBAL_CACHE_DIR_NAME;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.initialization.GradleUserHomeDirProvider;

import java.io.File;

@ServiceScope(Scopes.UserHome.class)
public class GlobalCacheDir {
    private final File globalCacheDir;

    public GlobalCacheDir(GradleUserHomeDirProvider userHomeDirProvider) {
        this.globalCacheDir = new File(userHomeDirProvider.getGradleUserHomeDirectory(), GLOBAL_CACHE_DIR_NAME);
    }

    public File getDir() {
        return globalCacheDir;
    }
}