package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Describable;
import com.tyron.builder.cache.CleanupProgressMonitor;

public interface DirectoryCleanupAction extends Describable {
    boolean execute(CleanupProgressMonitor progressMonitor);
}
