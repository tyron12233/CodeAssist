package org.gradle.cache.internal;

import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.ServiceRegistration;

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
