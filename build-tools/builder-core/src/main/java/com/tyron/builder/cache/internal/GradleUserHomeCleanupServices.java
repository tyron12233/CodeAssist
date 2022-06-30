package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.service.ServiceRegistration;

public class GradleUserHomeCleanupServices {

    public void configure(
        ServiceRegistration registration,
        GlobalScopedCache globalScopedCache,
        Deleter deleter,
        GradleUserHomeDirProvider gradleUserHomeDirProvider,
        ProgressLoggerFactory progressLoggerFactory
    ) {
        UsedGradleVersions usedGradleVersions = new UsedGradleVersionsFromGradleUserHomeCaches(globalScopedCache);
        registration.add(UsedGradleVersions.class, usedGradleVersions);
        // register eagerly so stop() is triggered when services are being stopped
        registration.add(
            GradleUserHomeCleanupService.class,
            new GradleUserHomeCleanupService(deleter, gradleUserHomeDirProvider, globalScopedCache, usedGradleVersions, progressLoggerFactory)
        );
    }

}
