package com.tyron.builder.initialization.layout;

import static com.tyron.builder.cache.internal.scopes.DefaultCacheScopeMapping.GLOBAL_CACHE_DIR_NAME;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;

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