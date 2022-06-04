package com.tyron.builder.language.jvm.tasks;

import com.tyron.builder.api.tasks.Copy;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.language.base.internal.tasks.StaleOutputCleaner;
import com.tyron.builder.work.DisableCachingByDefault;

import javax.inject.Inject;

/**
 * Copies resources from their source to their target directory, potentially processing them.
 * Makes sure no stale resources remain in the target directory.
 */
@DisableCachingByDefault(because = "Not worth caching")
public class ProcessResources extends Copy {

    @Override
    protected void copy() {
        boolean cleanedOutputs = StaleOutputCleaner.cleanOutputs(getDeleter(), getOutputs().getPreviousOutputFiles(), getDestinationDir());
        super.copy();
        if (cleanedOutputs) {
            setDidWork(true);
        }
    }

    @Inject
    protected Deleter getDeleter() {
        throw new UnsupportedOperationException("Decorator takes care of injection");
    }
}
