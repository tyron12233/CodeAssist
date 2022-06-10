package org.gradle.initialization.layout;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.cache.internal.DefaultCleanupProgressMonitor;
import org.gradle.cache.internal.VersionSpecificCacheCleanupAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

@ServiceScope(Scopes.BuildSession.class)
public class ProjectCacheDir implements Stoppable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectCacheDir.class);

    private static final long MAX_UNUSED_DAYS_FOR_RELEASES_AND_SNAPSHOTS = 7;

    private final File dir;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final Deleter deleter;
    private boolean deleteOnStop = false;

    public ProjectCacheDir(File dir, ProgressLoggerFactory progressLoggerFactory, Deleter deleter) {
        this.dir = dir;
        this.progressLoggerFactory = progressLoggerFactory;
        this.deleter = deleter;
    }

    public File getDir() {
        return dir;
    }

    public void delete() {
        deleteOnStop = true;
    }

    @Override
    public void stop() {
        if (deleteOnStop) {
            try {
                deleter.deleteRecursively(dir);
            } catch (IOException e) {
                LOGGER.debug("Failed to delete unused project cache dir " + dir.getAbsolutePath(), e);
            }
            return;
        }
        VersionSpecificCacheCleanupAction cleanupAction = new VersionSpecificCacheCleanupAction(
                dir,
                MAX_UNUSED_DAYS_FOR_RELEASES_AND_SNAPSHOTS,
                deleter
        );
        String description = cleanupAction.getDisplayName();
        ProgressLogger progressLogger = progressLoggerFactory.newOperation(ProjectCacheDir.class).start(description, description);
        try {
            cleanupAction.execute(new DefaultCleanupProgressMonitor(progressLogger));
        } finally {
            progressLogger.completed();
        }
    }
}
