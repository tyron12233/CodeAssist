package org.gradle.cache.internal;

import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.cache.CleanupProgressMonitor;

public class DefaultCleanupProgressMonitor implements CleanupProgressMonitor {

    private final ProgressLogger progressLogger;

    private long deleted;
    private long skipped;

    public DefaultCleanupProgressMonitor(ProgressLogger progressLogger) {
        this.progressLogger = progressLogger;
    }

    @Override
    public void incrementDeleted() {
        deleted++;
        updateProgress();
    }

    @Override
    public void incrementSkipped() {
        incrementSkipped(1);
    }

    @Override
    public void incrementSkipped(long amount) {
        skipped += amount;
        updateProgress();
    }

    private void updateProgress() {
        progressLogger.progress(progressLogger.getDescription() + ": "
                                + mandatoryNumber(deleted, " entry", " entries") + " deleted"
                                + optionalNumber(", ", skipped, " skipped"));
    }

    private String mandatoryNumber(long value, String singular, String plural) {
        return value == 1 ? value + singular : value + plural;
    }

    private String optionalNumber(String separator, long value, String description) {
        return value == 0 ? "" : separator + value + description;
    }
}