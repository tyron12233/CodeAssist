package org.gradle.cache.internal;

import org.gradle.api.Describable;
import org.gradle.cache.CleanupProgressMonitor;

public interface DirectoryCleanupAction extends Describable {
    boolean execute(CleanupProgressMonitor progressMonitor);
}
