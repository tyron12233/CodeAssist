package com.tyron.builder.cache.internal;

import com.tyron.builder.util.GradleVersion;

import java.util.SortedSet;

public interface UsedGradleVersions {

    /**
     * Returns the set of Gradle versions known to be used.
     */
    SortedSet<GradleVersion> getUsedGradleVersions();

}
